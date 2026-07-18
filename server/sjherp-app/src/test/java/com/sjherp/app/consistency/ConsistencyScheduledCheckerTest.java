package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import com.sjherp.domain.consistency.ConsistencyCheckRun;

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

    private static ConsistencyCheckRun cleanRun() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        return ConsistencyCheckRun.completed(0, "CHK-202607-0001",
                ConsistencyCheckRun.TriggerType.SCHEDULED, "system:consistency-scheduler",
                now, now, ConsistencyCheckRun.AnalysisStatus.SKIPPED, null, List.of());
    }
}
