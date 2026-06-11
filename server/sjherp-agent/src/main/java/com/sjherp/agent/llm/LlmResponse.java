package com.sjherp.agent.llm;

import java.util.List;
import java.util.Map;

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

    /** 是否包含工具调用请求 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 模型发起的一次工具调用请求。
     *
     * @param toolName  工具名称（必须已在 ToolRegistry 注册）
     * @param arguments 调用参数（已解析为键值对）
     */
    public record ToolCall(String toolName, Map<String, Object> arguments) {
    }
}
