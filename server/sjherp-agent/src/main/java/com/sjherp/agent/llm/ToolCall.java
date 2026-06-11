package com.sjherp.agent.llm;

/**
 * 模型发起的一次工具调用请求（厂商无关的统一表示）。
 *
 * <p>既出现在 {@link LlmResponse}（模型请求调用工具），也出现在
 * {@link LlmMessage}（把带工具调用的 assistant 消息回灌给模型时）。
 *
 * @param id            厂商分配的调用 id（回灌工具结果时用作 tool_call_id 关联）
 * @param name          工具名称（必须已在 ToolRegistry 注册）
 * @param argumentsJson 调用参数的原始 JSON 字符串（解析与校验由上层负责，
 *                      本模块零依赖、不引 JSON 库）
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolCall.name 不能为空");
        }
    }
}
