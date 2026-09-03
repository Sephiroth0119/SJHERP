package com.sjherp.app.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueCandidateRepository;
import com.sjherp.domain.gap.GapIssueClusterWriter;
import com.sjherp.domain.gap.GapIssueDeliveryFinalizer;
import com.sjherp.domain.gap.GapIssueStatus;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.gap.GapStatus;
import com.sjherp.infra.persistence.gap.JdbcGapIssueCandidateRepository;
import com.sjherp.infra.persistence.gap.JdbcGapRecordRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration-db")
class GapIssueTransactionalIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static GapIssueCandidateRepository candidates;
    private static GapIssueClusterWriter writer;
    private static GapIssueDeliveryFinalizer finalizer;
    private static GapRecordRepository gaps;
    private static FailingGapRecordService failingGapService;

    @BeforeAll
    static void startDatabase() {
        MYSQL.start();
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        jdbc = context.getBean(JdbcTemplate.class);
        candidates = context.getBean(GapIssueCandidateRepository.class);
        writer = context.getBean(GapIssueClusterWriter.class);
        finalizer = context.getBean(GapIssueDeliveryFinalizer.class);
        gaps = context.getBean(GapRecordRepository.class);
        failingGapService = (FailingGapRecordService) context.getBean(GapRecordService.class);
    }

    @AfterAll
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void clusterWriterRollsBackCandidateWhenSourceForeignKeyFails() {
        String key = "writer-rollback-" + uniqueSuffix();

        assertThatThrownBy(() -> writer.write(candidate(key, GapIssueStatus.PENDING, List.of()),
                List.of("GAP-MISSING-" + uniqueSuffix()), "reviewer"))
                .isInstanceOf(RuntimeException.class);

        assertThat(candidateCount(key)).isZero();
    }

    @Test
    void deliveryFinalizerRollsBackEarlierTriagedGapWhenLaterTransitionFails() {
        // JdbcGapIssueCandidateRepository reloads sources ordered by gap_no. The
        // A/Z prefixes prove that the first transition is written before the
        // second source deliberately fails, so the final assertions exercise a
        // real transaction rollback rather than a failure on the first item.
        String okGap = "GAP-A-OK-" + uniqueSuffix();
        String failingGap = "GAP-Z-FAIL-" + uniqueSuffix();
        failingGapService.resetSuccessfulTransitions();
        gaps.save(gap(okGap));
        gaps.save(gap(failingGap));

        String key = "finalizer-rollback-" + uniqueSuffix();
        GapIssueCandidate saved = candidates.upsert(candidate(key, GapIssueStatus.PENDING, List.of(okGap, failingGap)));
        candidates.addSources(saved.id(), List.of(okGap, failingGap));
        candidates.markApproved(saved.id(), "reviewer");
        String lease = candidates.claimForSend(saved.id()).orElseThrow();

        assertThatThrownBy(() -> finalizer.finalizeDelivery(saved, lease, 99, "https://example.test/99", "reviewer"))
                .isInstanceOf(IllegalStateException.class);

        // The service completed the A transition before Z threw. The database
        // assertions below therefore prove that the surrounding transaction
        // rolled the completed TRIAGED update back.
        assertThat(failingGapService.successfulTransitions()).containsExactly(okGap);
        assertThat(gaps.findByGapNo(okGap).orElseThrow().getStatus()).isEqualTo(GapStatus.NEW);
        assertThat(candidates.findById(saved.id()).orElseThrow().status()).isEqualTo(GapIssueStatus.SENDING);
    }

    private static GapIssueCandidate candidate(String key, GapIssueStatus status, List<String> sources) {
        return new GapIssueCandidate(0, key, key, BusinessModule.GENERAL, GapSeverity.LOW,
                "title", List.of("scenario"), "expected", "missing", sources, status,
                null, null, null, null, null, 0, Instant.now(), Instant.now(), null);
    }

    private static GapRecord gap(String gapNo) {
        return new GapRecord(gapNo, null, "title", "scenario", "expected", "missing",
                BusinessModule.GENERAL, GapSeverity.LOW, "reporter", "creator");
    }

    private static long candidateCount(String key) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM gap_issue_candidate "
                + "WHERE tenant_id=0 AND idempotency_key=?", Long.class, key);
        return count == null ? -1 : count;
    }

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        GapIssueCandidateRepository gapIssueCandidateRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcGapIssueCandidateRepository(jdbcTemplate, new ObjectMapper());
        }

        @Bean
        GapRecordRepository gapRecordRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcGapRecordRepository(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator() {
            return new DocumentNumberGenerator() {
                @Override
                public String generate(DocumentNumberRule rule) {
                    return "GAP-" + uniqueSuffix();
                }

                @Override
                public String generate(DocumentNumberRule rule, YearMonth yearMonth) {
                    return generate(rule);
                }
            };
        }

        @Bean
        GapRecordService gapRecordService(
                GapRecordRepository gapRecordRepository,
                DocumentNumberGenerator documentNumberGenerator) {
            return new FailingGapRecordService(gapRecordRepository, documentNumberGenerator);
        }

        @Bean
        GapIssueClusterWriter gapIssueClusterWriter(
                GapIssueCandidateRepository candidates,
                GapRecordRepository gaps,
                GapRecordService gapRecordService) {
            return new TransactionalGapIssueClusterWriter(candidates, gaps, gapRecordService);
        }

        @Bean
        GapIssueDeliveryFinalizer gapIssueDeliveryFinalizer(
                GapRecordRepository gaps,
                GapRecordService gapRecordService,
                GapIssueCandidateRepository candidates) {
            return new TransactionalGapIssueDeliveryFinalizer(gaps, gapRecordService, candidates);
        }
    }

    static class FailingGapRecordService extends GapRecordService {
        private final List<String> successfulTransitions = new ArrayList<>();

        FailingGapRecordService(GapRecordRepository repository, DocumentNumberGenerator numberGenerator) {
            super(repository, numberGenerator);
        }

        @Override
        public GapRecord transitionStatusByGapNo(String gapNo, GapStatus target, String operator) {
            if (gapNo.contains("GAP-Z-FAIL-")) {
                throw new IllegalStateException("forced gap transition failure");
            }
            GapRecord transitioned = super.transitionStatusByGapNo(gapNo, target, operator);
            successfulTransitions.add(gapNo);
            return transitioned;
        }

        void resetSuccessfulTransitions() {
            successfulTransitions.clear();
        }

        List<String> successfulTransitions() {
            return List.copyOf(successfulTransitions);
        }
    }
}
