package com.sjherp.agent.llm;

import java.util.List;

import com.sjherp.agent.session.MessageRole;

/**
 * 提交给 LLM 的一条消息（厂商无关的统一表示）。
 *
 * <p>除常规的 system / user / assistant 文本消息外，还支持工具调用往返：
 * <ul>
 *   <li>带 {@code toolCalls} 的 ASSISTANT 消息——把模型上一轮的工具调用请求回灌进上下文；</li>
 *   <li>TOOL 角色消息——工具执行结果，必须携带 {@code toolCallId} 与请求关联。</li>
 * </ul>
 *
 * @param role       角色（复用会话消息角色枚举）
 * @param content    文本内容（纯工具调用的 assistant 消息可为 null）
 * @param toolCalls  工具调用列表，仅 ASSISTANT 角色可非空
 * @param toolCallId 工具结果关联的调用 id，仅 TOOL 角色必填
 */
public record LlmMessage(MessageRole role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public LlmMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (!toolCalls.isEmpty() && role != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("只有 ASSISTANT 消息可以携带 toolCalls（role=" + role + "）");
        }
        if (role == MessageRole.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("TOOL 消息必须携带 toolCallId");
        }
        if (role != MessageRole.TOOL && toolCallId != null) {
            throw new IllegalArgumentException("只有 TOOL 消息可以携带 toolCallId（role=" + role + "）");
        }
    }

    /** 纯文本消息的便捷构造（兼容旧调用方） */
    public LlmMessage(MessageRole role, String content) {
        this(role, content, List.of(), null);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(MessageRole.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(MessageRole.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(MessageRole.ASSISTANT, content);
    }

    /** 带工具调用的 assistant 消息（回灌模型上一轮的 tool_calls；content 可为 null） */
    public static LlmMessage assistant(String content, List<ToolCall> toolCalls) {
        return new LlmMessage(MessageRole.ASSISTANT, content, toolCalls, null);
    }

    /** 工具执行结果消息（content 为工具结果的序列化文本） */
    public static LlmMessage tool(String toolCallId, String content) {
        return new LlmMessage(MessageRole.TOOL, content, List.of(), toolCallId);
    }

    /** 是否携带工具调用 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
