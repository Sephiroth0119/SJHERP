package com.sjherp.app.ops;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sjherp.app.ops.LlmDailySummaryService.LlmDailySummary;

/**
 * UTC 日切后写一条 LLM 用量日志；可选推 webhook（默认关，避免空报告刷屏）。
 */
@Component
@ConditionalOnProperty(prefix = "sjherp.ops", name = "llm-daily-enabled", havingValue = "true",
        matchIfMissing = true)
public class LlmDailySummaryJob {

    private static final Logger log = LoggerFactory.getLogger(LlmDailySummaryJob.class);

    private final LlmDailySummaryService summaryService;
    private final OpsProperties properties;
    private final AlertWebhookClient webhookClient;

    public LlmDailySummaryJob(LlmDailySummaryService summaryService,
                              OpsProperties properties,
                              AlertWebhookClient webhookClient) {
        this.summaryService = Objects.requireNonNull(summaryService, "summaryService 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.webhookClient = Objects.requireNonNull(webhookClient, "webhookClient 不能为空");
    }

    @Scheduled(cron = "${sjherp.ops.llm-daily-cron:0 5 0 * * *}", zone = "UTC")
    public void publishYesterday() {
        LlmDailySummary summary = summaryService.summarize(summaryService.yesterdayUtc().toString());
        log.info("LLM 用量日报 date={} calls={} failures={} promptTokens={} completionTokens={} "
                        + "durationMs={} estimatedCost={} ({})",
                summary.date(), summary.llmCalls(), summary.llmFailures(),
                summary.promptTokens(), summary.completionTokens(), summary.durationMs(),
                summary.estimatedCost() == null ? "-" : summary.estimatedCost(),
                summary.costNote());
        if (properties.alertLlmDaily()) {
            webhookClient.send("INFO", formatWebhook(summary));
        }
    }

    static String formatWebhook(LlmDailySummary summary) {
        String cost = summary.estimatedCost() == null
                ? "未估价"
                : summary.estimatedCost() + " " + summary.currency();
        return "SJHERP LLM 日报 " + summary.date()
                + " 调用 " + summary.llmCalls()
                + "（失败 " + summary.llmFailures() + "）"
                + " prompt=" + summary.promptTokens()
                + " completion=" + summary.completionTokens()
                + " 估算=" + cost
                + "。" + summary.costNote();
    }
}
