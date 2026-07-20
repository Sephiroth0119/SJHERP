package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.agent.session.AgentSessionRepository;
import com.sjherp.app.audit.AuditAspect;
import com.sjherp.app.audit.AuditMetrics;
import com.sjherp.app.audit.TransactionAwareAuditWriter;
import com.sjherp.app.memory.MemoryService;
import com.sjherp.app.memory.MemoryWriteChannel;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.DeveloperAgentTaskRepository;
import com.sjherp.domain.gap.ClosureFeedbackRepository;
import com.sjherp.domain.gap.ClosureEvidence;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.notification.SystemNotificationRepository;
import com.sjherp.infra.persistence.JdbcAgentSessionRepository;
import com.sjherp.infra.persistence.audit.JdbcAuditLogRepository;
import com.sjherp.infra.persistence.gap.JdbcClosureFeedbackRepository;
import com.sjherp.infra.persistence.gap.JdbcDeveloperAgentTaskRepository;
import com.sjherp.infra.persistence.gap.JdbcGapIssueCandidateRepository;
import com.sjherp.infra.persistence.gap.JdbcGapRecordRepository;
import com.sjherp.infra.persistence.memory.JdbcMemoryEntryRepository;
import com.sjherp.infra.persistence.notification.JdbcSystemNotificationRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Spring proxy + MySQL proof for the T10 closure feedback boundary. */
@Tag("integration-db")
class ClosureFeedbackTransactionalIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static String suffix;

    @BeforeAll static void start() {
        MYSQL.start();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }
    @AfterAll static void stop() { if (context != null) context.close(); MYSQL.stop(); }
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM audit_log"); jdbc.update("DELETE FROM system_notification");
        jdbc.update("DELETE FROM memory_entry"); jdbc.update("DELETE FROM closure_feedback");
        jdbc.update("DELETE FROM gap_issue_source"); jdbc.update("DELETE FROM developer_agent_task"); jdbc.update("DELETE FROM gap_issue_candidate");
        jdbc.update("DELETE FROM gap_record"); jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM sys_user WHERE id IN (11,12)");
        context.getBean(FailingMemoryWriteChannel.class).fail.set(false);
        suffix = Long.toString(System.nanoTime(), 36);
    }

    @Test void approvedTaskUsesRealProxyAndPersistsAllClosureOutputs() {
        Fixture f = fixture();
        context.getBean(ClosureFeedbackService.class).confirm(f.taskId,
                new ClosureEvidence("ci://" + suffix, "fixed task " + f.taskId), "admin");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM closure_feedback WHERE task_id=?", Integer.class, f.taskId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gap_record WHERE gap_no LIKE ? AND status='RESOLVED'", Integer.class, "GAP-" + suffix + "%")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_entry WHERE memory_type='GAP_SOLUTION' AND status='ACTIVE' AND source_type='GAP_RECORD' AND source_ref LIKE ? AND content LIKE ? AND content LIKE ? AND content LIKE ? AND content LIKE ? AND content LIKE ?", Integer.class, "task:%", "%candidate%", "%GAP-" + suffix + "-1%", "%GAP-" + suffix + "-2%", "%GAP-" + suffix + "-3%", "%ci://" + suffix + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification WHERE source_type='GAP_CLOSURE' AND source_ref=?", Integer.class, "task:" + f.taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification WHERE recipient_user_id=11", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification WHERE recipient_user_id=12", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE operator='admin' AND action='developer.task.confirm_resolution' AND target_id=?", Integer.class, f.taskId)).isEqualTo(1);
    }

    @Test void downstreamFailureRollsBackClaimGapsMemoryNotificationsAndAudit() {
        Fixture f = fixture();
        context.getBean(FailingMemoryWriteChannel.class).fail.set(true);
        assertThatThrownBy(() -> context.getBean(ClosureFeedbackService.class).confirm(f.taskId,
                new ClosureEvidence("ci://fail", "fail"), "admin")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM closure_feedback", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gap_record WHERE status='IN_DEVELOPMENT'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_entry", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isZero();
        context.getBean(FailingMemoryWriteChannel.class).fail.set(false);
        context.getBean(ClosureFeedbackService.class).confirm(f.taskId,
                new ClosureEvidence("ci://retry", "recovered"), "admin");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM closure_feedback", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gap_record WHERE status='RESOLVED'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_entry WHERE status='ACTIVE' AND memory_type='GAP_SOLUTION' AND source_type='GAP_RECORD' AND source_ref LIKE ? AND content LIKE ? AND content LIKE ? AND content LIKE ? AND content LIKE ? AND content LIKE ?", Integer.class, "task:%", "%cand-" + suffix + "%", "%GAP-" + suffix + "-1%", "%GAP-" + suffix + "-2%", "%GAP-" + suffix + "-3%", "%ci://retry%recovered%" )).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification WHERE source_type='GAP_CLOSURE' AND source_ref=?", Integer.class, "task:" + f.taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification WHERE recipient_user_id=11", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification WHERE recipient_user_id=12", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE operator='admin' AND action='developer.task.confirm_resolution' AND target_id=?", Integer.class, f.taskId)).isEqualTo(1);
    }

    @Test void concurrentCallsThroughSameProxyAreIdempotent() throws Exception {
        Fixture f = fixture();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var call = (java.util.concurrent.Callable<Void>) () -> { ready.countDown(); start.await();
                context.getBean(ClosureFeedbackService.class).confirm(f.taskId, new ClosureEvidence("ci://concurrent", "same solution"), "admin"); return null; };
            Future<Void> a = pool.submit(call), b = pool.submit(call); assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
            a.get(10, java.util.concurrent.TimeUnit.SECONDS); b.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM closure_feedback", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_entry WHERE status='ACTIVE' AND memory_type='GAP_SOLUTION'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM system_notification", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gap_record WHERE status='RESOLVED'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='developer.task.confirm_resolution'", Integer.class)).isEqualTo(2);
    }

    private record Fixture(long taskId, long candidateId, String candidateKey, List<String> gapNos) {}
    private Fixture fixture() {
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,roles,status,created_by,created_at,updated_by,updated_at) VALUES(11,0,?,'User 11','test','[\"USER\"]','ENABLED','test',UTC_TIMESTAMP(6),'test',UTC_TIMESTAMP(6)),(12,0,?,'User 12','test','[\"USER\"]','ENABLED','test',UTC_TIMESTAMP(6),'test',UTC_TIMESTAMP(6))", "u11-"+suffix, "u12-"+suffix);
        jdbc.update("INSERT INTO agent_session(id,user_id,title,status,created_at,updated_at) VALUES(?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", "session-A-"+suffix,"11","A", "session-B-"+suffix,"11","B");
        jdbc.update("INSERT INTO gap_record(tenant_id,gap_no,session_id,title,scenario,expected_behavior,missing_capability,business_module,severity,status,reporter,created_by,created_at,updated_by,updated_at) VALUES(0,?,?,?,'scenario','expected','missing','GENERAL','LOW','IN_DEVELOPMENT','11','11',UTC_TIMESTAMP(6),'11',UTC_TIMESTAMP(6))", "GAP-"+suffix+"-1", "session-A-"+suffix, "title");
        jdbc.update("INSERT INTO gap_record(tenant_id,gap_no,session_id,title,scenario,expected_behavior,missing_capability,business_module,severity,status,reporter,created_by,created_at,updated_by,updated_at) VALUES(0,?,?,?,'scenario','expected','missing','GENERAL','LOW','IN_DEVELOPMENT','11','11',UTC_TIMESTAMP(6),'11',UTC_TIMESTAMP(6))", "GAP-"+suffix+"-2", "session-B-"+suffix, "title");
        jdbc.update("INSERT INTO gap_record(tenant_id,gap_no,session_id,title,scenario,expected_behavior,missing_capability,business_module,severity,status,reporter,created_by,created_at,updated_by,updated_at) VALUES(0,?,?,?,'scenario','expected','missing','GENERAL','LOW','IN_DEVELOPMENT','12','12',UTC_TIMESTAMP(6),'12',UTC_TIMESTAMP(6))", "GAP-"+suffix+"-3", "missing-session-"+suffix, "title");
        KeyHolder candidateKeys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO gap_issue_candidate(tenant_id,idempotency_key,cluster_key,business_module,severity,title,scenario_samples,expected_behavior,missing_capability,status,created_at,updated_at) VALUES(0,?,?, 'GENERAL','LOW','title',JSON_ARRAY('scenario'),'expected','missing','APPROVED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "cand-"+suffix); ps.setString(2, "cluster-"+suffix); return ps;
        }, candidateKeys);
        long candidate = candidateKeys.getKey().longValue();
        KeyHolder taskKeys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO developer_agent_task(tenant_id,candidate_id,idempotency_key,status,branch_name,workspace_path,runner_kind,generated_artifacts,created_by,created_at,updated_at) VALUES(0,?,?, 'APPROVED','branch','workspace','REST','[]','admin',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, candidate); ps.setString(2, "task-"+suffix); return ps;
        }, taskKeys);
        long task = taskKeys.getKey().longValue();
        jdbc.update("INSERT INTO gap_issue_source(tenant_id,candidate_id,gap_no,created_at) VALUES(0,?,?,UTC_TIMESTAMP(6)),(0,?,?,UTC_TIMESTAMP(6)),(0,?,?,UTC_TIMESTAMP(6))", candidate, "GAP-"+suffix+"-1", candidate, "GAP-"+suffix+"-2", candidate, "GAP-"+suffix+"-3");
        return new Fixture(task, candidate, "cand-"+suffix, List.of("GAP-"+suffix+"-1", "GAP-"+suffix+"-2", "GAP-"+suffix+"-3"));
    }

    @Configuration @EnableTransactionManagement @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean ApplicationEventPublisher events() { return event -> {}; }
        @Bean DocumentNumberGenerator numbers() { return new DocumentNumberGenerator() {
            public String generate(com.sjherp.domain.common.numbering.DocumentNumberRule rule) { return "MEM-" + System.nanoTime(); }
            public String generate(com.sjherp.domain.common.numbering.DocumentNumberRule rule, java.time.YearMonth month) { return generate(rule); }
        }; }
        @Bean GapRecordRepository gaps(JdbcTemplate j) { return new JdbcGapRecordRepository(j); }
        @Bean GapIssueCandidateRepository candidates(JdbcTemplate j,ObjectMapper o) { return new JdbcGapIssueCandidateRepository(j,o); }
        @Bean DeveloperAgentTaskRepository tasks(JdbcTemplate j,ObjectMapper o) { return new JdbcDeveloperAgentTaskRepository(j,o); }
        @Bean ClosureFeedbackRepository closures(JdbcTemplate j) { return new JdbcClosureFeedbackRepository(j); }
        @Bean AgentSessionRepository sessions(JdbcTemplate j) { return new JdbcAgentSessionRepository(j); }
        @Bean SystemNotificationRepository notifications(JdbcTemplate j) { return new JdbcSystemNotificationRepository(j); }
        @Bean MemoryEntryRepository memoryRepo(JdbcTemplate j) { return new JdbcMemoryEntryRepository(j); }
        @Bean MemoryService memoryService(MemoryEntryRepository r,DocumentNumberGenerator n,ApplicationEventPublisher e) { return new MemoryService(r,n,e); }
        @Bean @Primary FailingMemoryWriteChannel memory(MemoryService s) { return new FailingMemoryWriteChannel(s); }
        @Bean AuditMetrics auditMetrics() { return new AuditMetrics(); }
        @Bean TransactionAwareAuditWriter auditWriter(JdbcTemplate j,AuditMetrics m) { return new TransactionAwareAuditWriter(new JdbcAuditLogRepository(j),m); }
        @Bean AuditAspect auditAspect(TransactionAwareAuditWriter w,AuditMetrics m) { return new AuditAspect(w,m); }
        @Bean ClosureFeedbackService closureFeedbackService(DeveloperAgentTaskRepository t,GapIssueCandidateRepository c,GapRecordRepository g,ClosureFeedbackRepository f,MemoryWriteChannel m,SystemNotificationRepository n,AgentSessionRepository s) { return new ClosureFeedbackService(t,c,g,f,m,n,s); }
    }
    static class FailingMemoryWriteChannel extends MemoryWriteChannel {
        final AtomicBoolean fail = new AtomicBoolean(); FailingMemoryWriteChannel(MemoryService s) { super(s); }
        @Override public com.sjherp.domain.memory.MemoryEntry approveAndWrite(com.sjherp.domain.memory.StructuredMemoryCandidate c,String o) { if (fail.get()) throw new IllegalStateException("downstream failure"); return super.approveAndWrite(c,o); }
    }
}
