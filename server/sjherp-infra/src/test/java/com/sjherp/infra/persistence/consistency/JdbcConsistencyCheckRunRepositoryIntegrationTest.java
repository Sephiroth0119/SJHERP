package com.sjherp.infra.persistence.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyFinding;
import com.sjherp.domain.consistency.ConsistencyRunQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

class JdbcConsistencyCheckRunRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcConsistencyCheckRunRepository repository = new JdbcConsistencyCheckRunRepository(jdbc);

    @Tag("integration-db")
    @Test
    void savesRunWithDecimalFindingsAndReadsPageAndDetail() {
        String runNo = "CHK-IT-" + uniqueSuffix();
        repository.save(runWithFinding(runNo, new BigDecimal("10.123456")));

        assertThat(repository.findByRunNo(0, runNo)).get()
                .satisfies(run -> {
                    assertThat(run.errorCount()).isEqualTo(1L);
                    assertThat(run.findings()).extracting(ConsistencyFinding::sequenceNo).containsExactly(1, 2);
                    assertThat(run.findings().get(0).expectedValue()).isEqualByComparingTo("10.123456");
                    assertThat(run.findings().get(0).actualValue()).isEqualByComparingTo("0.000000");
                    assertThat(run.findings().get(1).expectedValue()).isNull();
                    assertThat(run.findings().get(1).actualValue()).isNull();
                });
        assertThat(repository.search(0, new ConsistencyRunQuery(1, 20)).items())
                .filteredOn(run -> run.runNo().equals(runNo))
                .singleElement()
                .satisfies(run -> assertThat(run.findings()).isEmpty());
    }

    @Tag("integration-db")
    @Test
    void savesCleanRunWithoutFindings() {
        String runNo = "CHK-IT-" + uniqueSuffix();
        Instant startedAt = Instant.parse("2026-07-19T00:00:00Z");
        repository.save(ConsistencyCheckRun.completed(0, runNo, ConsistencyCheckRun.TriggerType.SCHEDULED,
                "tester", startedAt, startedAt.plusSeconds(1),
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of()));

        assertThat(repository.findByRunNo(0, runNo)).get()
                .satisfies(run -> {
                    assertThat(run.clean()).isTrue();
                    assertThat(run.findings()).isEmpty();
                });
    }

    private static ConsistencyCheckRun runWithFinding(String runNo, BigDecimal expectedValue) {
        Instant startedAt = Instant.parse("2026-07-19T00:00:00Z");
        Instant completedAt = startedAt.plusSeconds(1);
        return ConsistencyCheckRun.completed(0, runNo, ConsistencyCheckRun.TriggerType.MANUAL_API,
                "tester", startedAt, completedAt, ConsistencyCheckRun.AnalysisStatus.SKIPPED, null,
                List.of(
                        new ConsistencyFinding(2, "IT-NULL", "SQL_ASSERTION", "nullable-object",
                                null, null, ConsistencyFinding.Severity.WARN, "nullable round trip"),
                        new ConsistencyFinding(1, "IT-RULE", "SQL_ASSERTION", "test-object",
                                expectedValue, new BigDecimal("0.000000"),
                                ConsistencyFinding.Severity.ERROR, "decimal round trip")));
    }
}
