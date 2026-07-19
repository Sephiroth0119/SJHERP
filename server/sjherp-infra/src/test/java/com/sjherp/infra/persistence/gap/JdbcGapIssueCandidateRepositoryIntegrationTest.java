package com.sjherp.infra.persistence.gap;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.gap.GapIssueCandidate;
import com.sjherp.infra.persistence.MySqlContainerTestBase;
import org.junit.jupiter.api.Test;

class JdbcGapIssueCandidateRepositoryIntegrationTest extends MySqlContainerTestBase {
    @Test
    void migrationCreatesCandidateAndSourceTables() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='gap_issue_candidate'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='gap_issue_source'", Integer.class)).isEqualTo(1);
    }

    @Test
    void repositoryRoundTripClaimAndCasFailure() {
        JdbcGapIssueCandidateRepository repository = new JdbcGapIssueCandidateRepository(jdbc, new ObjectMapper());
        String key = "it-" + uniqueSuffix();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO gap_issue_candidate(tenant_id,idempotency_key,cluster_key,business_module,severity,title,scenario_samples,expected_behavior,missing_capability,status,attempt_count,created_at,updated_at) VALUES(0,?,?,?,?,?,?,?,?,?,0,?,?)", key, key, "GENERAL", "LOW", "integration", "[\"scenario\"]", "expected", "missing", "APPROVED", now, now);
        GapIssueCandidate loaded = repository.findById(jdbc.queryForObject("SELECT id FROM gap_issue_candidate WHERE idempotency_key=?", Long.class, key)).orElseThrow();
        String token = repository.claimForSend(loaded.id()).orElseThrow();
        assertThat(repository.claimForSend(loaded.id())).isEmpty();
        repository.markFailed(loaded.id(), token, "HTTP");
        assertThat(repository.findById(loaded.id()).orElseThrow().failureType()).isEqualTo("HTTP");
    }
}
