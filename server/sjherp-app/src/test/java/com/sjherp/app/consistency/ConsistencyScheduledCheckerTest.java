package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.TaskUtils;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.consistency.ConsistencyCheckRun;

@ExtendWith(OutputCaptureExtension.class)
class ConsistencyScheduledCheckerTest {

    @Test
    void scheduledEntryUsesRunnerAndKeepsConfigurationContract() throws Exception {
        ConsistencyCheckRunner runner = mock(ConsistencyCheckRunner.class);
        when(runner.runScheduled()).thenReturn(cleanRun());
        ConsistencyScheduledChecker checker = new ConsistencyScheduledChecker(runner);

        checker.runScheduledCheck();

        verify(runner).runScheduled();
        Scheduled scheduled = ConsistencyScheduledChecker.class
                .getMethod("runScheduledCheck").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${sjherp.consistency.cron:0 0 3 * * *}");
        ConditionalOnProperty conditional = ConsistencyScheduledChecker.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(conditional.prefix()).isEqualTo("sjherp.consistency");
        assertThat(conditional.name()).containsExactly("enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isFalse();
    }

    @Test
    void scheduledDeterministicFailureLogsOnlySafeRunMetadata(CapturedOutput output) {
        String runNo = "CHK-202607-0099";
        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        when(numberGenerator.generate(DocumentNumberRule.of("CHK"))).thenReturn(runNo);
        ConsistencyRule failingRule = new ConsistencyRule() {
            @Override public String code() { return "SECRET_SQL"; }
            @Override public int order() { return 1; }
            @Override public Kind kind() { return Kind.SQL_ASSERTION; }
            @Override public Result evaluate(Context context) {
                throw new SecretDeterministicFailure(
                        "jdbc:secret-password; full finding message");
            }
        };
        ConsistencyCheckRunner runner = new ConsistencyCheckRunner(
                new ConsistencyRuleRegistry(List.of(failingRule)), numberGenerator,
                mock(ConsistencyRunPersistenceService.class));
        ConsistencyScheduledChecker checker = new ConsistencyScheduledChecker(runner);

        Throwable escaped = catchThrowable(checker::runScheduledCheck);
        if (escaped != null) {
            TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.handleError(escaped);
        }

        assertThat(output.getAll())
                .contains(runNo, "总数=0", "ERROR=0", "WARN=0", "INFO=0")
                .doesNotContain("jdbc:secret-password", "full finding message",
                        "SecretDeterministicFailure",
                        "scheduledDeterministicFailureLogsOnlySafeRunMetadata");
        assertThat(escaped).isNull();
    }

    @Test
    void scheduledCompletionPersistenceFailureLogsOnlySafeRunMetadata(CapturedOutput output) {
        String runNo = "CHK-202607-0100";
        DocumentNumberGenerator numberGenerator = mock(DocumentNumberGenerator.class);
        when(numberGenerator.generate(DocumentNumberRule.of("CHK"))).thenReturn(runNo);
        ConsistencyRule successfulRule = new ConsistencyRule() {
            @Override public String code() { return "SQL"; }
            @Override public int order() { return 1; }
            @Override public Kind kind() { return Kind.SQL_ASSERTION; }
            @Override public Result evaluate(Context context) { return Result.deterministic(List.of()); }
        };
        ConsistencyRunPersistenceService persistence = mock(ConsistencyRunPersistenceService.class);
        org.mockito.Mockito.doThrow(new SecretCompletionPersistenceFailure(
                "jdbc:secret-password; full finding message"))
                .when(persistence).persist(org.mockito.ArgumentMatchers.any());
        ConsistencyCheckRunner runner = new ConsistencyCheckRunner(
                new ConsistencyRuleRegistry(List.of(successfulRule)), numberGenerator, persistence);
        ConsistencyScheduledChecker checker = new ConsistencyScheduledChecker(runner);

        Throwable escaped = catchThrowable(checker::runScheduledCheck);
        if (escaped != null) {
            TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER.handleError(escaped);
        }

        assertThat(output.getAll())
                .contains(runNo, "总数=0", "ERROR=0", "WARN=0", "INFO=0")
                .doesNotContain("jdbc:secret-password", "full finding message",
                        "SecretCompletionPersistenceFailure",
                        "scheduledCompletionPersistenceFailureLogsOnlySafeRunMetadata");
        assertThat(escaped).isNull();
    }

    private static ConsistencyCheckRun cleanRun() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler",
                now, now, ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of());
    }

    private static final class SecretDeterministicFailure extends RuntimeException {
        private SecretDeterministicFailure(String message) {
            super(message);
        }
    }

    private static final class SecretCompletionPersistenceFailure extends RuntimeException {
        private SecretCompletionPersistenceFailure(String message) {
            super(message);
        }
    }
}
