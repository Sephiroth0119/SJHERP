package com.sjherp.agent.llm;

import java.util.List;

/**
 * LLM 回复（厂商无关的统一表示）。
 *
 * @param content   文本回复，可为 null（纯工具调用时）
 * @param toolCalls 模型请求的工具调用列表，可为空
 */
public record LlmResponse(String content, List<ToolCall> toolCalls) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 纯文本回复的便捷构造 */
    public LlmResponse(String content) {
        this(content, List.of());
    }

    /** 是否包含工具调用请求 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
