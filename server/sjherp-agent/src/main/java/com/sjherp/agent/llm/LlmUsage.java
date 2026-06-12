package com.sjherp.agent.llm;

/**
 * 一次 LLM 调用的 token 用量（厂商无关的统一表示，M1-T06 可观测性 / X-6 成本看板数据源）。
 *
 * <p>OpenAI 兼容响应的 usage 字段（prompt_tokens / completion_tokens）由各厂商实现解析填充；
 * 厂商未返回 usage 时整体为 null（见 {@link LlmResponse#usage()}）。
 *
 * @param promptTokens     输入（prompt）token 数，可为 null（厂商未提供该项）
 * @param completionTokens 输出（completion）token 数，可为 null（厂商未提供该项）
 */
public record LlmUsage(Integer promptTokens, Integer completionTokens) {
}
