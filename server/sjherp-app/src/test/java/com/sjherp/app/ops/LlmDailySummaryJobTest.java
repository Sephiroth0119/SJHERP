package com.sjherp.app.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sjherp.app.ops.LlmDailySummaryService.LlmDailySummary;

@ExtendWith(MockitoExtension.class)
class LlmDailySummaryJobTest {

    @Mock
    private LlmDailySummaryService summaryService;

    @Mock
    private AlertWebhookClient webhookClient;

    @Test
    void 默认不推日报webhook() {
        OpsProperties properties = new OpsProperties("http://hook", true, "0 5 0 * * *", false, "", "");
        LlmDailySummary summary = sample("2026-10-20");
        when(summaryService.yesterdayUtc()).thenReturn(LocalDate.parse("2026-10-20"));
        when(summaryService.summarize("2026-10-20")).thenReturn(summary);
        new LlmDailySummaryJob(summaryService, properties, webhookClient).publishYesterday();
        verifyNoInteractions(webhookClient);
    }

    @Test
    void 打开开关才推INFO() {
        OpsProperties properties = new OpsProperties("http://hook", true, "0 5 0 * * *", true, "", "");
        LlmDailySummary summary = sample("2026-10-20");
        when(summaryService.yesterdayUtc()).thenReturn(LocalDate.parse("2026-10-20"));
        when(summaryService.summarize("2026-10-20")).thenReturn(summary);
        new LlmDailySummaryJob(summaryService, properties, webhookClient).publishYesterday();
        verify(webhookClient).send("INFO", LlmDailySummaryJob.formatWebhook(summary));
        assertThat(LlmDailySummaryJob.formatWebhook(summary)).contains("2026-10-20").contains("未估价");
    }

    private static LlmDailySummary sample(String date) {
        return new LlmDailySummary(date, date + "T00:00:00Z", date + "T00:00:00Z",
                4, 1, 10, 2, 40, null, null, "未配置单价", List.of());
    }
}
