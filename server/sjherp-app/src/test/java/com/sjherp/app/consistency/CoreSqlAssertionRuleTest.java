package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.consistency.ConsistencyCheckRun;

class CoreSqlAssertionRuleTest {

    private static final Instant INSTANT = Instant.parse("2026-07-19T00:00:00Z");
    private static final ConsistencyRule.Context CONTEXT = new ConsistencyRule.Context(
            7L, "CHK-001", ConsistencyCheckRun.TriggerType.MANUAL_API, "tester");

    @Test
    void coreAdapterReturnsExistingReportWithoutRecomputingRules() {
        ConsistencyCheckService service = mock(ConsistencyCheckService.class);
        ConsistencyBreak breakItem = mock(ConsistencyBreak.class);
        when(service.check()).thenReturn(new ConsistencyReport(INSTANT, List.of(breakItem)));

        ConsistencyRule.Result result = new CoreSqlAssertionRule(service).evaluate(CONTEXT);

        assertThat(result.breaks()).containsExactly(breakItem);
        assertThat(result.analysisSummary()).isNull();
        verify(service).check();
    }
}
