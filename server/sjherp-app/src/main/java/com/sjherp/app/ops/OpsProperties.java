package com.sjherp.app.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 运维监控配置（M8-T04，前缀 {@code sjherp.ops}）。
 *
 * <p>Webhook 与 LLM 单价均来自环境变量；单价留空则日报只汇总 token，不估算金额。
 */
@ConfigurationProperties(prefix = "sjherp.ops")
public record OpsProperties(
        String alertWebhook,
        @DefaultValue("true") boolean llmDailyEnabled,
        @DefaultValue("0 5 0 * * *") String llmDailyCron,
        @DefaultValue("false") boolean alertLlmDaily,
        String llmPromptPricePerMillion,
        String llmCompletionPricePerMillion) {
}
