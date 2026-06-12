package com.sjherp.agent.llm;

import java.util.List;

/**
 * LLM 回复（厂商无关的统一表示）。
 *
 * @param content   文本回复，可为 null（纯工具调用时）
 * @param toolCalls 模型请求的工具调用列表，可为空
 * @param model     实际应答的模型名（取自厂商响应，可为 null；M1-T06 可观测性记录用）
 * @param usage     本次调用的 token 用量（厂商未返回 usage 时为 null）
 */
public record LlmResponse(String content, List<ToolCall> toolCalls, String model, LlmUsage usage) {

    public LlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 纯文本回复的便捷构造 */
    public LlmResponse(String content) {
        this(content, List.of(), null, null);
    }

    /** 不带观测信息（model/usage）的便捷构造（既有调用方与测试替身用） */
    public LlmResponse(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null, null);
    }

    /** 是否包含工具调用请求 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
