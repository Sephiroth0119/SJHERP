package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.stereotype.Component;

import com.sjherp.app.consistency.ConsistencyRule.Kind;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.consistency.ConsistencyCheckRun.AnalysisStatus;
import com.sjherp.domain.consistency.ConsistencyCheckRun.TriggerType;

class ConsistencyCheckRunnerTest {

    private static final Instant NOW = Instant.parse("2026-07-19T01:02:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ConsistencyBreak BREAK = ConsistencyBreak.of(
            ConsistencyCheckType.LEDGER_COST, "warehouse=1,product=2",
            new BigDecimal("10.123456"), new BigDecimal("9.000000"),
            ConsistencySeverity.ERROR, "full finding message");

    private final DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
    private final ConsistencyRunPersistenceService persistence = mock(ConsistencyRunPersistenceService.class);

    @Test
    void persistsEverySuccessfulRunAndSkipsLlmWhenNoneRegistered() {
        when(numberGenerator.generate(DocumentNumberRule.of("CHK"))).thenReturn("CHK-202607-0001");
        ConsistencyCheckRunner runner = runner(registry(sqlRuleReturning("SQL", BREAK)));

        ConsistencyCheckRun run = runner.runManual("admin");

        assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.SKIPPED);
        assertThat(run.triggerType()).isEqualTo(TriggerType.MANUAL_API);
        assertThat(run.requestedBy()).isEqualTo("admin");
        assertThat(run.runNo()).isEqualTo("CHK-202607-0001");
        verify(numberGenerator).generate(DocumentNumberRule.of("CHK"));
        verify(persistence).persist(run);
    }

    @Test
    void executesEverySqlRuleBeforeLlmRulesAndMapsFindingsSafely() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0002");
        List<String> calls = new ArrayList<>();
        ConsistencyRule sqlB = rule("SQL_B", 20, Kind.SQL_ASSERTION, context -> {
            calls.add("SQL_B");
            return ConsistencyRule.Result.deterministic(List.of(BREAK));
        });
        ConsistencyRule sqlA = rule("SQL_A", 10, Kind.SQL_ASSERTION, context -> {
            calls.add("SQL_A");
            return ConsistencyRule.Result.deterministic(List.of());
        });
        ConsistencyRule llm = rule("LLM", 1, Kind.LLM_ANALYSIS, context -> {
            calls.add("LLM");
            return ConsistencyRule.Result.analysis("safe analysis");
        });

        ConsistencyCheckRun run = runner(registry(sqlB, llm, sqlA)).runScheduled();

        assertThat(calls).containsExactly("SQL_A", "SQL_B", "LLM");
        assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(run.analysisSummary()).isEqualTo("safe analysis");
        assertThat(run.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.sequenceNo()).isEqualTo(1);
            assertThat(finding.ruleCode()).isEqualTo("SQL_B");
            assertThat(finding.checkType()).isEqualTo("LEDGER_COST");
            assertThat(finding.expectedValue()).isEqualByComparingTo("10.123456");
            assertThat(finding.actualValue()).isEqualByComparingTo("9.000000");
            assertThat(finding.message()).isEqualTo("full finding message");
        });
    }

    @Test
    void llmFailureDoesNotDiscardDeterministicFindings() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0003");
        ConsistencyRule llmFailure = rule("LLM", 1, Kind.LLM_ANALYSIS, context -> {
            throw new IllegalStateException("secret");
        });

        ConsistencyCheckRun run = runner(registry(
                sqlRuleReturning("SQL", BREAK), llmFailure)).runAgent("7");

        assertThat(run.findings()).hasSize(1);
        assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(run.failureType()).isNull();
        assertThat(run.requestedBy()).isEqualTo("agent:7");
    }

    @Test
    void llmFailureDoesNotTurnCleanRunIntoBusinessAnomaly() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0004");
        ConsistencyRule llmFailure = rule("LLM", 1, Kind.LLM_ANALYSIS, context -> {
            throw new IllegalArgumentException("model secret");
        });

        ConsistencyCheckRun run = runner(registry(
                sqlRuleReturning("SQL"), llmFailure)).runAgent("8");

        assertThat(run.clean()).isTrue();
        assertThat(run.totalCount()).isZero();
        assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.FAILED);
    }

    @Test
    void manualDeterministicFailurePersistsSafeFailedRunThenRethrowsOriginal() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0005");
        IllegalStateException failure = new IllegalStateException("database password");
        ConsistencyRule sqlFailure = rule("SQL", 1, Kind.SQL_ASSERTION, context -> {
            throw failure;
        });

        assertThatThrownBy(() -> runner(registry(sqlFailure)).runManual("admin"))
                .isSameAs(failure);
        ArgumentCaptor<ConsistencyCheckRun> saved = ArgumentCaptor.forClass(ConsistencyCheckRun.class);
        verify(persistence).persist(saved.capture());
        assertThat(saved.getValue().triggerType()).isEqualTo(TriggerType.MANUAL_API);
        assertThat(saved.getValue().failureType()).isEqualTo("IllegalStateException");
        assertThat(saved.getValue().auditSummary()).doesNotContain("database password");
        assertThat(saved.getValue().findings()).isEmpty();
    }

    @Test
    void agentFailedSummaryPersistenceCannotReplaceOriginalDeterministicFailure() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0006");
        IllegalStateException original = new IllegalStateException("deterministic secret");
        IllegalArgumentException persistenceFailure = new IllegalArgumentException("storage secret");
        ConsistencyRule sqlFailure = rule("SQL", 1, Kind.SQL_ASSERTION, context -> {
            throw original;
        });
        org.mockito.Mockito.doThrow(persistenceFailure).when(persistence).persist(any());

        Throwable thrown = catchThrowable(() -> runner(registry(sqlFailure)).runAgent("7"));

        assertThat(thrown).isSameAs(original);
    }

    @Test
    void scheduledDeterministicFailureReturnsPersistedSafeFailedOutcome() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0009");
        ConsistencyRule sqlFailure = rule("SQL", 1, Kind.SQL_ASSERTION, context -> {
            throw new IllegalStateException("scheduled database password");
        });

        ConsistencyCheckRun run = runner(registry(sqlFailure)).runScheduled();

        assertThat(run.runNo()).isEqualTo("CHK-202607-0009");
        assertThat(run.triggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(run.status()).isEqualTo(ConsistencyCheckRun.Status.FAILED);
        assertThat(run.totalCount()).isZero();
        assertThat(run.errorCount()).isZero();
        assertThat(run.warnCount()).isZero();
        assertThat(run.infoCount()).isZero();
        assertThat(run.auditSummary()).doesNotContain("scheduled database password");
        verify(persistence).persist(run);
    }

    @Test
    void allLlmRulesAreAttemptedButAnyFailureMakesAnalysisFailed() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0007");
        ConsistencyRule first = rule("LLM_A", 1, Kind.LLM_ANALYSIS, context -> {
            throw new IllegalStateException("secret");
        });
        ConsistencyRule second = mock(ConsistencyRule.class);
        when(second.code()).thenReturn("LLM_B");
        when(second.order()).thenReturn(2);
        when(second.kind()).thenReturn(Kind.LLM_ANALYSIS);
        when(second.evaluate(any())).thenReturn(ConsistencyRule.Result.analysis("available summary"));

        ConsistencyCheckRun run = runner(registry(sqlRuleReturning("SQL"), first, second)).runScheduled();

        assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(run.analysisSummary()).isEqualTo("available summary");
        verify(second).evaluate(any());
        verify(persistence, times(1)).persist(run);
    }

    @Test
    void capsCombinedLlmSummaryWithoutLosingCompletedRunOrDeterministicFindings() {
        when(numberGenerator.generate(any())).thenReturn("CHK-202607-0008");
        String firstSummary = "A".repeat(600);
        String secondSummary = "B".repeat(600);
        ConsistencyRule first = rule("LLM_A", 1, Kind.LLM_ANALYSIS,
                context -> ConsistencyRule.Result.analysis(firstSummary));
        ConsistencyRule second = rule("LLM_B", 2, Kind.LLM_ANALYSIS,
                context -> ConsistencyRule.Result.analysis(secondSummary));

        ConsistencyCheckRun run = runner(registry(
                sqlRuleReturning("SQL", BREAK), first, second)).runScheduled();

        assertThat(run.status()).isEqualTo(ConsistencyCheckRun.Status.COMPLETED);
        assertThat(run.analysisStatus()).isEqualTo(AnalysisStatus.SUCCEEDED);
        assertThat(run.analysisSummary())
                .hasSize(1000)
                .isEqualTo(firstSummary + "\n" + "B".repeat(399));
        assertThat(run.findings()).hasSize(1);
        assertThat(run.totalCount()).isEqualTo(1);
        verify(persistence).persist(run);
    }

    @Test
    void manualEntryAloneCarriesAuditConventionAndRegistryIsSpringManaged() throws Exception {
        Audited audited = ConsistencyCheckRunner.class.getMethod("runManual", String.class)
                .getAnnotation(Audited.class);

        assertThat(audited).isNotNull();
        assertThat(audited.action()).isEqualTo("consistency.run");
        assertThat(audited.targetType()).isEqualTo("consistency_report");
        assertThat(ConsistencyCheckRunner.class.getMethod("runAgent", String.class)
                .getAnnotation(Audited.class)).isNull();
        assertThat(ConsistencyCheckRunner.class.getMethod("runScheduled")
                .getAnnotation(Audited.class)).isNull();
        assertThat(ConsistencyRuleRegistry.class).hasAnnotation(Component.class);
    }

    private ConsistencyCheckRunner runner(ConsistencyRuleRegistry registry) {
        return new ConsistencyCheckRunner(registry, numberGenerator, persistence, CLOCK);
    }

    private static ConsistencyRuleRegistry registry(ConsistencyRule... rules) {
        return new ConsistencyRuleRegistry(List.of(rules));
    }

    private static ConsistencyRule sqlRuleReturning(String code, ConsistencyBreak... breaks) {
        return rule(code, 1, Kind.SQL_ASSERTION,
                context -> ConsistencyRule.Result.deterministic(List.of(breaks)));
    }

    private static ConsistencyRule rule(String code, int order, Kind kind, Evaluator evaluator) {
        return new ConsistencyRule() {
            @Override public String code() { return code; }
            @Override public int order() { return order; }
            @Override public Kind kind() { return kind; }
            @Override public Result evaluate(Context context) { return evaluator.evaluate(context); }
        };
    }

    @FunctionalInterface
    private interface Evaluator {
        ConsistencyRule.Result evaluate(ConsistencyRule.Context context);
    }
}
