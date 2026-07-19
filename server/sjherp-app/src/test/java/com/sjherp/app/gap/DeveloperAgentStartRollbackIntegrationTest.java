package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gap.*;
import com.sjherp.infra.persistence.gap.JdbcDeveloperAgentTaskRepository;
import com.sjherp.infra.persistence.gap.JdbcGapRecordRepository;
import com.sjherp.app.audit.*;
import com.sjherp.infra.persistence.audit.*;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration-db")
class DeveloperAgentStartRollbackIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @Test
    void startUsesSpringTransactionAndRollsBackTaskAndFirstGap() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String gapNo = "GAP-T09-1-" + suffix;
        String secondGapNo = "GAP-T09-2-" + suffix;
        jdbc.update("INSERT INTO gap_record(tenant_id,gap_no,title,scenario,expected_behavior,missing_capability,business_module,severity,status,reporter,created_by,created_at,updated_by,updated_at) VALUES(0,?,?,?,?,?,'GENERAL','LOW','TRIAGED','test','test',UTC_TIMESTAMP(6),'test',UTC_TIMESTAMP(6))",
                gapNo, "title", "scenario", "expected", "missing");
        jdbc.update("INSERT INTO gap_record(tenant_id,gap_no,title,scenario,expected_behavior,missing_capability,business_module,severity,status,reporter,created_by,created_at,updated_by,updated_at) VALUES(0,?,?,?,?,?,'GENERAL','LOW','TRIAGED','test','test',UTC_TIMESTAMP(6),'test',UTC_TIMESTAMP(6))",
                secondGapNo, "title", "scenario", "expected", "missing");
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO gap_issue_candidate(tenant_id,idempotency_key,cluster_key,business_module,severity,title,scenario_samples,expected_behavior,missing_capability,status,issue_number,created_at,updated_at) VALUES(0,?,?, 'GENERAL','LOW','title','[\"scenario\"]','expected','missing','SENT',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "rollback-" + suffix);
            ps.setString(2, "cluster-" + suffix);
            return ps;
        }, keys);
        long candidateId = keys.getKey().longValue();
        GapIssueCandidate candidate = new GapIssueCandidate(candidateId, "rollback-" + suffix, "cluster-" + suffix,
                BusinessModule.GENERAL, GapSeverity.LOW, "title", java.util.List.of("scenario"), "expected", "missing",
                java.util.List.of(gapNo, secondGapNo), GapIssueStatus.SENT, 1L, "url", null, null, null, 0, null, null, null);
        GapIssueCandidateRepository candidates = context.getBean(GapIssueCandidateRepository.class);
        when(candidates.findById(candidateId)).thenReturn(java.util.Optional.of(candidate));
        GapRecordRepository gaps = context.getBean(GapRecordRepository.class);
        java.util.concurrent.atomic.AtomicInteger secondReads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (invocation.getArgument(0, String.class).equals(secondGapNo)
                    && secondReads.incrementAndGet() > 1) return java.util.Optional.empty();
            return ((JdbcGapRecordRepository) context.getBean("realGapRepository")).findByGapNo(invocation.getArgument(0, String.class));
        }).when(gaps).findByGapNo(org.mockito.ArgumentMatchers.anyString());

        DeveloperAgentService service = context.getBean(DeveloperAgentService.class);
        assertThatThrownBy(() -> service.start(candidateId, "admin")).isInstanceOf(GapRecordNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM developer_agent_task WHERE candidate_id=?", Integer.class, candidateId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM gap_record WHERE gap_no=?", String.class, gapNo)).isEqualTo("TRIAGED");
        assertThat(jdbc.queryForObject("SELECT status FROM gap_record WHERE gap_no=?", String.class, secondGapNo)).isEqualTo("TRIAGED");
    }

    @Test
    void failureServiceWritesFailureEvidenceAndAudit() {
        String suffix = Long.toString(System.nanoTime(), 36);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("INSERT INTO gap_issue_candidate(tenant_id,idempotency_key,cluster_key,business_module,severity,title,scenario_samples,expected_behavior,missing_capability,status,issue_number,created_at,updated_at) VALUES(0,?,?, 'GENERAL','LOW','title','[\"scenario\"]','expected','missing','SENT',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "failure-" + suffix); ps.setString(2, "failure-cluster-" + suffix); return ps;
        }, keys);
        long candidateId = keys.getKey().longValue();
        DeveloperAgentTaskRepository tasks = context.getBean(DeveloperAgentTaskRepository.class);
        DeveloperAgentTask created = tasks.createIfAbsent(new DeveloperAgentTask(0, candidateId, "failure-task-" + suffix,
                DeveloperAgentTaskStatus.QUEUED, "codex/dev/failure-" + suffix, "C:/repo/failure-" + suffix,
                "REST", null, 0, List.of(), false, false, false, null, false, null, null, null), "admin");
        String lease = tasks.claim(created.id(), java.time.Instant.now()).orElseThrow();
        DeveloperAgentFailureService failure = context.getBean(DeveloperAgentFailureService.class);
        failure.fail(created.id(), DeveloperAgentTaskStatus.RUNNING, lease, "GATEWAY", "timeout",
                List.of("code.java", "test.java"), true, false, false, "ci://timeout", "runner-output", "admin");
        DeveloperAgentTask failed = tasks.findById(created.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(DeveloperAgentTaskStatus.FAILED);
        assertThat(failed.generatedArtifacts()).containsExactly("code.java", "test.java");
        assertThat(failed.failureType()).isEqualTo("GATEWAY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='developer.task.fail' AND target_id=? AND operator=?", Integer.class, created.id(), "admin")).isEqualTo(1);
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {
        @Bean DataSource dataSource() { return new org.springframework.jdbc.datasource.DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean AuditMetrics auditMetrics() { return new AuditMetrics(); }
        @Bean AuditLogRepository auditLogRepository(JdbcTemplate jdbc) { return new JdbcAuditLogRepository(jdbc); }
        @Bean TransactionAwareAuditWriter transactionAwareAuditWriter(AuditLogRepository repository, AuditMetrics metrics) { return new TransactionAwareAuditWriter(repository, metrics); }
        @Bean AuditAspect auditAspect(TransactionAwareAuditWriter writer, AuditMetrics metrics) { return new AuditAspect(writer, metrics); }
        @Bean GapIssueCandidateRepository candidateRepository() { return mock(GapIssueCandidateRepository.class); }
        @Bean DeveloperAgentTaskRepository taskRepository(JdbcTemplate jdbc, ObjectMapper json) { return new JdbcDeveloperAgentTaskRepository(jdbc, json); }
        @Bean(name = "realGapRepository") JdbcGapRecordRepository realGapRepository(JdbcTemplate jdbc) { return new JdbcGapRecordRepository(jdbc); }
        @Bean GapRecordRepository gapRepository(JdbcGapRecordRepository real) { return spy(real); }
        @Bean DocumentNumberGenerator numberGenerator() { return new DocumentNumberGenerator() { public String generate(DocumentNumberRule rule) { return "TEST-1"; } public String generate(DocumentNumberRule rule, java.time.YearMonth month) { return "TEST-1"; } }; }
        @Bean GapRecordService gapService(GapRecordRepository repository, DocumentNumberGenerator generator) { return new GapRecordService(repository, generator); }
        @Bean DeveloperAgentRunner runner() { return new DisabledDeveloperAgentRunner(); }
        @Bean WorkspacePolicy workspacePolicy() { return new WorkspacePolicy(Path.of("").toAbsolutePath()); }
        @Bean DeveloperAgentFailureService failureService(DeveloperAgentTaskRepository tasks) { return new DeveloperAgentFailureService(tasks); }
        @Bean DeveloperAgentService developerAgentService(GapIssueCandidateRepository candidates, DeveloperAgentTaskRepository tasks, GapRecordRepository gaps, GapRecordService gapService, DeveloperAgentRunner runner, WorkspacePolicy workspacePolicy, DeveloperAgentFailureService failureService) { return new DeveloperAgentService(candidates, tasks, gaps, gapService, runner, workspacePolicy, failureService); }
    }
}
