package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.domain.gap.GapIssueStatus;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.infra.persistence.MySqlContainerTestBase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcGapIssueCandidateRepositoryIntegrationTest extends MySqlContainerTestBase {
    private final JdbcGapIssueCandidateRepository repository = new JdbcGapIssueCandidateRepository(jdbc, new ObjectMapper());

    @Test
    void migrationCreatesCandidateAndSourceTables() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='gap_issue_candidate'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name='gap_issue_source'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void duplicateUpsertAtomicallyMergesApprovedSamplesWithoutChangingRepresentative() {
        String key = "merge-" + uniqueSuffix();
        GapIssueCandidate initial = repository.upsert(candidate(key, List.of("first", "duplicate")));
        repository.markApproved(initial.id(), "boss");

        List<String> laterSamples = new ArrayList<>();
        laterSamples.add("duplicate");
        for (int index = 0; index < 25; index++) {
            laterSamples.add("later-" + index);
        }
        GapIssueCandidate merged = repository.upsert(candidate(key, laterSamples));

        assertThat(merged.id()).isEqualTo(initial.id());
        assertThat(merged.status()).isEqualTo(GapIssueStatus.APPROVED);
        assertThat(merged.title()).isEqualTo("first title");
        assertThat(merged.scenarioSamples()).hasSize(20)
                .containsExactly("first", "duplicate", "later-0", "later-1", "later-2", "later-3",
                        "later-4", "later-5", "later-6", "later-7", "later-8", "later-9", "later-10",
                        "later-11", "later-12", "later-13", "later-14", "later-15", "later-16", "later-17");
    }

    @Test
    void concurrentDuplicateUpsertsCreateOneCandidateAndPreserveBothSamples() throws Exception {
        String key = "concurrent-" + uniqueSuffix();
        CyclicBarrier ready = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        try {
            Future<?> first = pool.submit(() -> upsertAfterBarrier(transactions, ready, key, "first"));
            Future<?> second = pool.submit(() -> upsertAfterBarrier(transactions, ready, key, "second"));
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        GapIssueCandidate stored = findByKey(key);
        assertThat(stored.scenarioSamples()).containsExactlyInAnyOrder("first", "second");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gap_issue_candidate "
                        + "WHERE tenant_id=0 AND idempotency_key=?", Integer.class, key))
                .isEqualTo(1);
    }

    @Test
    void staleLeaseTokenIsRejectedAfterReclaimAndNewClaimWhileCurrentLeaseCanMarkSent() {
        GapIssueCandidate candidate = approvedCandidate("lease-" + uniqueSuffix());
        String staleToken = repository.claimForSend(candidate.id()).orElseThrow();
        jdbc.update("UPDATE gap_issue_candidate SET sending_started_at=? WHERE tenant_id=0 AND id=?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20), candidate.id());

        assertThat(repository.reclaimExpiredSending(Instant.now().minusSeconds(60))).isEqualTo(1);
        GapIssueCandidate reclaimed = repository.findById(candidate.id()).orElseThrow();
        assertThat(reclaimed.status()).isEqualTo(GapIssueStatus.FAILED);
        assertThat(reclaimed.sendingStartedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM gap_issue_candidate WHERE tenant_id=0 AND id=?",
                String.class, candidate.id())).isNull();

        String currentToken = repository.claimForSend(candidate.id()).orElseThrow();
        assertThat(currentToken).isNotEqualTo(staleToken);
        assertThatThrownBy(() -> repository.markSent(candidate.id(), staleToken, 77, "https://example.test/77"))
                .isInstanceOf(IllegalStateException.class);

        repository.markSent(candidate.id(), currentToken, 77, "https://example.test/77");
        GapIssueCandidate sent = repository.findById(candidate.id()).orElseThrow();
        assertThat(sent.status()).isEqualTo(GapIssueStatus.SENT);
        assertThat(sent.issueNumber()).isEqualTo(77);
        assertThat(sent.issueUrl()).isEqualTo("https://example.test/77");
        assertThat(sent.sendingStartedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM gap_issue_candidate WHERE tenant_id=0 AND id=?",
                String.class, candidate.id())).isNull();
    }

    @Test
    void sourceRowsAreAppendOnlyAndBothForeignKeysAreEnforced() {
        GapIssueCandidate candidate = repository.upsert(candidate("source-" + uniqueSuffix(), List.of("source")));
        String gapNo = "GAP-SOURCE-" + uniqueSuffix();
        insertGap(gapNo);

        repository.addSources(candidate.id(), List.of(gapNo, gapNo));
        assertThat(repository.findById(candidate.id()).orElseThrow().sourceGapNos()).containsExactly(gapNo);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO gap_issue_source(tenant_id,candidate_id,gap_no,created_at) "
                        + "VALUES(0,?,?,?)", candidate.id(), "GAP-NOT-FOUND-" + uniqueSuffix(),
                LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO gap_issue_source(tenant_id,candidate_id,gap_no,created_at) "
                        + "VALUES(0,?,?,?)", Long.MAX_VALUE, gapNo, LocalDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void candidateAndSourceWritesRollBackTogetherWhenSourceInsertFails() {
        String key = "rollback-" + uniqueSuffix();
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            GapIssueCandidate saved = repository.upsert(candidate(key, List.of("scenario")));
            repository.addSources(saved.id(), List.of("GAP-MISSING-" + uniqueSuffix()));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gap_issue_candidate "
                        + "WHERE tenant_id=0 AND idempotency_key=?", Integer.class, key))
                .isZero();
    }

    private void upsertAfterBarrier(
            TransactionTemplate transactions,
            CyclicBarrier barrier,
            String key,
            String scenario) {
        transactions.executeWithoutResult(status -> {
            try {
                barrier.await();
            } catch (Exception exception) {
                throw new IllegalStateException("cannot synchronize concurrent upsert", exception);
            }
            repository.upsert(candidate(key, List.of(scenario)));
        });
    }

    private GapIssueCandidate approvedCandidate(String key) {
        GapIssueCandidate candidate = repository.upsert(candidate(key, List.of("scenario")));
        repository.markApproved(candidate.id(), "boss");
        return repository.findById(candidate.id()).orElseThrow();
    }

    private GapIssueCandidate findByKey(String key) {
        long id = jdbc.queryForObject("SELECT id FROM gap_issue_candidate "
                + "WHERE tenant_id=0 AND idempotency_key=?", Long.class, key);
        return repository.findById(id).orElseThrow();
    }

    private GapIssueCandidate candidate(String key, List<String> samples) {
        return new GapIssueCandidate(0, key, key, BusinessModule.GENERAL, GapSeverity.LOW,
                "first title", samples, "expected", "missing", List.of(), GapIssueStatus.PENDING,
                null, null, null, null, null, 0, null, null, null);
    }

    private void insertGap(String gapNo) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO gap_record(tenant_id,gap_no,title,scenario,expected_behavior,missing_capability,"
                        + "business_module,severity,status,reporter,created_by,created_at,updated_by,updated_at) "
                        + "VALUES(0,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                gapNo,
                "title",
                "scenario",
                "expected",
                "missing",
                "GENERAL",
                "LOW",
                "NEW",
                "reporter",
                "test",
                now,
                "test",
                now);
    }
}
