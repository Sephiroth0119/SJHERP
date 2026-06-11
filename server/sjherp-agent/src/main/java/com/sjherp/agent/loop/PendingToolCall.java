package com.sjherp.agent.loop;

import java.util.List;
import java.util.Objects;

import com.sjherp.agent.llm.ToolCall;

/**
 * 待人工确认的高风险工具调用（M1-T03 高风险拦截的中断现场）。
 *
 * <p>执行循环遇到 riskLevel=HIGH 且未带确认标记的调用时不执行、中断循环并返回本对象。
 * 它携带恢复执行所需的全部现场（模型该轮的工具调用消息 + 已执行调用的结果），
 * 由上层序列化为 JSON 存入会话（agent_session.pending_tool_call 列，infra 的
 * PendingToolCallJsonCodec 编解码）；用户点击确认 / 取消后经
 * {@link AgentLoop#resume} 恢复循环。
 *
 * @param assistantContent  模型发起该轮工具调用时的文本内容（可为 null）
 * @param toolCalls         该轮 assistant 消息携带的全部工具调用
 * @param executedResults   拦截前已执行调用的结果（保持执行顺序，恢复时原样回灌）
 * @param pendingToolCallId 等待确认的调用 id（必须存在于 {@code toolCalls} 中）
 * @param summary           人类可读摘要（工具说明 + 参数），用于生成确认卡片文案
 */
public record PendingToolCall(String assistantContent, List<ToolCall> toolCalls,
                              List<ExecutedResult> executedResults,
                              String pendingToolCallId, String summary) {

    /** 已执行调用的结果（toolCallId 与回灌内容） */
    public record ExecutedResult(String toolCallId, String content) {
    }

    public PendingToolCall {
        Objects.requireNonNull(pendingToolCallId, "pendingToolCallId 不能为空");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        executedResults = executedResults == null ? List.of() : List.copyOf(executedResults);
        List<ToolCall> calls = toolCalls;
        if (calls.stream().noneMatch(call -> pendingToolCallId.equals(call.id()))) {
            throw new IllegalArgumentException("pendingToolCallId 不在 toolCalls 中: " + pendingToolCallId);
        }
    }

    /** 等待确认的那个调用 */
    public ToolCall pendingCall() {
        return toolCalls.stream()
                .filter(call -> pendingToolCallId.equals(call.id()))
                .findFirst()
                .orElseThrow(); // 构造时已校验存在
    }

    /** 等待确认的工具名（便于上层展示与审计） */
    public String toolName() {
        return pendingCall().name();
    }

    /** 等待确认的调用参数 JSON（便于上层展示与审计） */
    public String argumentsJson() {
        return pendingCall().argumentsJson();
    }
}
