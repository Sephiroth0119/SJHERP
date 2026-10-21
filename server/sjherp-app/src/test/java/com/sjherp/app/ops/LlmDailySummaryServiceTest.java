package com.sjherp.app.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sjherp.app.ops.LlmDailySummaryService.LlmDailySummary;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository.LlmUsageWindow;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository.ModelBreakdown;

@ExtendWith(MockitoExtension.class)
class LlmDailySummaryServiceTest {

    @Mock
    private AgentInvocationRepository repository;

    private LlmDailySummaryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC);
        OpsProperties properties = new OpsProperties("", true, "0 5 0 * * *", false, "2", "8");
        service = new LlmDailySummaryService(repository, properties, clock);
    }

    @Test
    void 默认日期是UTC今天并估算金额() {
        Instant from = Instant.parse("2026-10-21T00:00:00Z");
        Instant to = Instant.parse("2026-10-22T00:00:00Z");
        when(repository.summarizeLlm(from, to)).thenReturn(new LlmUsageWindow(
                from, to, 3, 1, 1_000_000, 250_000, 900,
                List.of(new ModelBreakdown("deepseek-chat", 3, 1, 1_000_000, 250_000, 900))));

        LlmDailySummary summary = service.summarize(null);
        assertThat(summary.date()).isEqualTo("2026-10-21");
        assertThat(summary.llmCalls()).isEqualTo(3);
        assertThat(summary.llmFailures()).isEqualTo(1);
        assertThat(summary.estimatedCost()).isEqualTo("4.0000");
        assertThat(summary.currency()).isEqualTo("CNY");
        assertThat(summary.costNote()).contains("非正式账单");
        assertThat(summary.byModel()).hasSize(1);
        verify(repository).summarizeLlm(from, to);
    }

    @Test
    void 未配单价则金额为空() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC);
        OpsProperties properties = new OpsProperties("", true, "0 5 0 * * *", false, "", "");
        service = new LlmDailySummaryService(repository, properties, clock);
        Instant from = Instant.parse("2026-10-20T00:00:00Z");
        Instant to = Instant.parse("2026-10-21T00:00:00Z");
        when(repository.summarizeLlm(from, to)).thenReturn(new LlmUsageWindow(
                from, to, 1, 0, 10, 2, 12, List.of()));

        LlmDailySummary summary = service.summarize("2026-10-20");
        assertThat(summary.estimatedCost()).isNull();
        assertThat(summary.currency()).isNull();
        assertThat(summary.costNote()).contains("只汇总 token");
    }

    @Test
    void 非法日期400口径() {
        assertThatThrownBy(() -> service.summarize("2026/10/21"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
        verify(repository, org.mockito.Mockito.never()).summarizeLlm(any(), any());
    }
}
