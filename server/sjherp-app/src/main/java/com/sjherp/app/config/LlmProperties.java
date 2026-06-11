package com.sjherp.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 接入配置（前缀 sjherp.llm）。
 *
 * <p>api-key 一律来自环境变量 SJHERP_LLM_API_KEY 或 local profile
 * （application-local.yml，已被 .gitignore 忽略），绝不写进任何会被
 * git 跟踪的文件。
 *
 * @param apiKey         DeepSeek API Key，缺省为空（空时聊天回退占位 Agent）
 * @param baseUrl        API 根地址
 * @param model          模型名
 * @param temperature    采样温度（模型参数，非金额/数量，可用 double）
 * @param timeoutSeconds 单次请求整体超时（秒）
 */
@ConfigurationProperties(prefix = "sjherp.llm")
public record LlmProperties(
        String apiKey,
        @DefaultValue("https://api.deepseek.com") String baseUrl,
        @DefaultValue("deepseek-chat") String model,
        @DefaultValue("0.7") double temperature,
        @DefaultValue("60") long timeoutSeconds) {

    /** api-key 是否已配置 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
