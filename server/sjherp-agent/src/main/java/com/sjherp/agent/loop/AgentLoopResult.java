package com.sjherp.agent.loop;

import java.util.List;

/**
 * 执行循环的结果：二选一——
 * <ul>
 *   <li><b>完成</b>：模型产出最终文本（聊天链路下为选项返回协议 JSON，由上层解析）；</li>
 *   <li><b>待确认</b>：遇到高风险工具被框架拦截，循环中断，携带恢复现场
 *       {@link PendingToolCall}（{@code finalText} 为 null）。</li>
 * </ul>
 * 两种情形都携带本轮全部工具调用记录（可观测性）。
 *
 * @param finalText       最终文本；待确认时为 null
 * @param toolCallRecords 本轮全部工具调用记录（名称 / 参数 / 结果 / 耗时）
 * @param pendingToolCall 待确认的高风险调用；完成时为 null
 */
public record AgentLoopResult(String finalText, List<ToolCallRecord> toolCallRecords,
                              PendingToolCall pendingToolCall) {

    public AgentLoopResult {
        toolCallRecords = toolCallRecords == null ? List.of() : List.copyOf(toolCallRecords);
        if ((finalText == null) == (pendingToolCall == null)) {
            throw new IllegalArgumentException("finalText 与 pendingToolCall 必须恰好一项非空");
        }
    }

    /** 是否因高风险拦截而中断（true 时上层须发起人工确认流程） */
    public boolean isPendingConfirmation() {
        return pendingToolCall != null;
    }

    static AgentLoopResult completed(String finalText, List<ToolCallRecord> records) {
        return new AgentLoopResult(finalText, records, null);
    }

    static AgentLoopResult pendingConfirmation(PendingToolCall pending, List<ToolCallRecord> records) {
        return new AgentLoopResult(null, records, pending);
    }
}
