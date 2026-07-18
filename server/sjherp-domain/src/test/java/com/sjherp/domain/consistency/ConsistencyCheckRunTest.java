package com.sjherp.domain.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConsistencyCheckRunTest {

    private static final Instant INSTANT = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void completedRunCopiesFindingsAndDerivesCounts() {
        ConsistencyFinding finding = new ConsistencyFinding(1, "CORE_SQL_ASSERTIONS",
                "LEDGER_COST", "warehouse=1,product=2", new BigDecimal("10.000000"),
                new BigDecimal("9.000000"), ConsistencyFinding.Severity.ERROR, "库存金额不平");
        ConsistencyCheckRun run = ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", INSTANT, INSTANT,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of(finding));

        assertThat(run.clean()).isFalse();
        assertThat(run.errorCount()).isEqualTo(1);
        assertThat(run.warnCount()).isZero();
        assertThat(run.infoCount()).isZero();
        assertThat(run.totalCount()).isEqualTo(1);
        assertThat(run.findings()).containsExactly(finding);
        assertThatThrownBy(() -> run.findings().add(finding))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failedRunStoresOnlyFailureTypeAndNoFindings() {
        ConsistencyCheckRun run = ConsistencyCheckRun.failed(0, "CHK-202607-0002",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler",
                INSTANT, INSTANT.plusSeconds(1), "IllegalStateException");

        assertThat(run.status()).isEqualTo(ConsistencyCheckRun.Status.FAILED);
        assertThat(run.failureType()).isEqualTo("IllegalStateException");
        assertThat(run.findings()).isEmpty();
        assertThat(run.totalCount()).isZero();
        assertThat(run.clean()).isFalse();
    }

    @Test
    void failedRestoreRejectsAnalysisSummaryAndNonSkippedAnalysisStatus() {
        assertThatThrownBy(() -> restore(ConsistencyCheckRun.Status.FAILED, false, 0, 0, 0, 0,
                ConsistencyCheckRun.AnalysisStatus.FAILED, null, "IllegalStateException", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restore(ConsistencyCheckRun.Status.FAILED, false, 0, 0, 0, 0,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, "不应保存", "IllegalStateException", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restore(ConsistencyCheckRun.Status.FAILED, false, 0, 0, 0, 0,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restore(ConsistencyCheckRun.Status.FAILED, true, 0, 0, 0, 0,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, "IllegalStateException", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restore(ConsistencyCheckRun.Status.FAILED, false, 1, 1, 0, 0,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, "IllegalStateException", List.of(finding(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedRestoreRejectsFailureType() {
        assertThatThrownBy(() -> restore(ConsistencyCheckRun.Status.COMPLETED, false, 1, 1, 0, 0,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, "IllegalStateException", List.of(finding(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedAndRestoredRunsRejectDuplicateFindingSequenceNumbers() {
        ConsistencyFinding first = finding(1);
        ConsistencyFinding duplicate = new ConsistencyFinding(1, "SECOND_RULE", "LEDGER_QTY",
                "warehouse=1,product=3", new BigDecimal("2.000000"), new BigDecimal("1.000000"),
                ConsistencyFinding.Severity.WARN, "库存数量不平");

        assertThatThrownBy(() -> ConsistencyCheckRun.completed(0, "CHK-202607-0003",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", INSTANT, INSTANT,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
        assertThatThrownBy(() -> restore(ConsistencyCheckRun.Status.COMPLETED, false, 2, 1, 1, 0,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, null, List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
    }

    @Test
    void decimalFindingValuesMustFitDecimal24Scale6WithoutRounding() {
        BigDecimal maximum = new BigDecimal("999999999999999999.999999");

        ConsistencyFinding accepted = new ConsistencyFinding(1, "RULE", "CHECK", null,
                maximum, new BigDecimal("1.2"), ConsistencyFinding.Severity.ERROR, "错误");

        assertThat(accepted.expectedValue()).isEqualTo(maximum);
        assertThat(accepted.actualValue()).isEqualTo(new BigDecimal("1.2"));
        assertThatThrownBy(() -> new ConsistencyFinding(2, "RULE", "CHECK", null,
                new BigDecimal("1000000000000000000.000000"), null,
                ConsistencyFinding.Severity.ERROR, "错误"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsistencyFinding(3, "RULE", "CHECK", null,
                null, new BigDecimal("0.0000001"), ConsistencyFinding.Severity.ERROR, "错误"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignedIdMustBePositiveAndCanOnlyBeAssignedOnce() {
        ConsistencyCheckRun run = completedRun();

        assertThatThrownBy(() -> run.assignId(0))
                .isInstanceOf(IllegalArgumentException.class);

        run.assignId(9);

        assertThat(run.id()).isEqualTo(9);
        assertThatThrownBy(() -> run.assignId(10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void auditSummaryContainsOnlyTriggerStatusAndCounts() {
        ConsistencyCheckRun run = completedRun();

        assertThat(run.auditTargetCode()).isEqualTo("CHK-202607-0001");
        assertThat(run.auditSummary()).contains("触发=MANUAL_API", "状态=COMPLETED", "总数=1")
                .doesNotContain("CHK-202607-0001", "admin", "库存金额不平");
    }

    @Test
    void rejectsInvalidFindingAndPagingParameters() {
        assertThatThrownBy(() -> new ConsistencyFinding(0, "RULE", "CHECK", null, null, null,
                ConsistencyFinding.Severity.ERROR, "错误"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序号");
        assertThatThrownBy(() -> new ConsistencyRunQuery(0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("分页参数不合法");
        assertThatThrownBy(() -> new ConsistencyRunQuery(1, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("分页参数不合法");
    }

    private static ConsistencyCheckRun completedRun() {
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", INSTANT, INSTANT,
                ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of(finding(1)));
    }

    private static ConsistencyCheckRun restore(ConsistencyCheckRun.Status status, boolean clean,
                                               long totalCount, long errorCount, long warnCount, long infoCount,
                                               ConsistencyCheckRun.AnalysisStatus analysisStatus,
                                               String analysisSummary, String failureType,
                                               List<ConsistencyFinding> findings) {
        return ConsistencyCheckRun.restore(9, 0, "CHK-202607-0004",
                ConsistencyCheckRun.TriggerType.MANUAL_API, "admin", INSTANT, INSTANT,
                status, clean, totalCount, errorCount, warnCount, infoCount, analysisStatus,
                analysisSummary, failureType, INSTANT, findings);
    }

    private static ConsistencyFinding finding(int sequenceNo) {
        return new ConsistencyFinding(sequenceNo, "CORE_SQL_ASSERTIONS", "LEDGER_COST",
                "warehouse=1,product=2", new BigDecimal("10.000000"), new BigDecimal("9.000000"),
                ConsistencyFinding.Severity.ERROR, "库存金额不平");
    }
}
