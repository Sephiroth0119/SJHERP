package com.sjherp.agent.loop;

import com.sjherp.agent.tool.ToolRiskLevel;

/**
 * Agent 调用观测回调（M1-T06）：{@link AgentLoop} 在每次 LLM 调用与每次工具调用
 * 处理完成后回调本接口，供上层做结构化落库（agent_invocation 表）/ 日志 / 成本统计（X-6）。
 *
 * <p>设计约束：
 * <ul>
 *   <li>零依赖：本接口属于框架（sjherp-agent），落库实现放 infra；</li>
 *   <li>不影响主流程：AgentLoop 对回调做 try-catch 吞掉一切异常——观测失败绝不能
 *       中断对话；实现方也应自行兜底（如落库失败只记日志）；</li>
 *   <li>同步回调：在循环线程上调用，实现方耗时操作应尽量轻（异步化留给实现方演进）。</li>
 * </ul>
 */
public interface AgentInvocationListener {

    /**
     * 一次 LLM 调用完成（成功或抛错）后回调。
     *
     * @param sessionId        会话 id（无审计上下文时可为 null）
     * @param round            本次执行循环内的 LLM 调用序号（1 起；含终轮单独 JSON 调用与强制收尾调用）
     * @param model            实际应答的模型名（取自厂商响应；调用失败未获响应时为 null）
     * @param durationMs       调用耗时（毫秒）
     * @param promptTokens     输入 token 数（厂商未返回 usage 时为 null）
     * @param completionTokens 输出 token 数（厂商未返回 usage 时为 null）
     * @param hasToolCalls     本次响应是否包含工具调用请求
     * @param error            错误信息（调用成功时为 null）
     */
    void onLlmCall(String sessionId, int round, String model, long durationMs,
                   Integer promptTokens, Integer completionTokens, boolean hasToolCalls, String error);

    /**
     * 一次工具调用处理完成后回调（含未知工具 / 参数校验失败 / 执行异常 / 用户取消等失败情形，
     * 口径与 {@link ToolCallRecord} 一致）。
     *
     * @param sessionId     会话 id（无审计上下文时可为 null）
     * @param toolName      工具名（模型给出的原始名称，未知工具也原样上报）
     * @param argumentsJson 模型给出的原始参数 JSON
     * @param success       工具是否成功执行
     * @param resultSummary 回灌给模型的结果摘要（框架已截断，见 {@link AgentLoop}）
     * @param durationMs    执行耗时（毫秒；未实际执行的情形为 0）
     * @param riskLevel     工具风险等级（未知工具为 null）
     * @param confirmed     本次执行是否经过用户高风险确认（resume 确认链路为 true）
     */
    void onToolCall(String sessionId, String toolName, String argumentsJson, boolean success,
                    String resultSummary, long durationMs, ToolRiskLevel riskLevel, boolean confirmed);

    /**
     * 一次 {@link AgentLoop} 之外的辅助 LLM 调用（如历史摘要，M1-T05/M1-T07）完成后回调。
     * 落库口径：type 仍为 LLM，purpose 进 detail（区分主链路调用）。
     *
     * <p>default 空实现：既有实现与测试替身无需感知本方法；调用方（如
     * {@code LlmHistorySummarizer}）须自行 try-catch——观测失败绝不能中断主流程。
     *
     * @param sessionId        会话 id（无会话上下文时可为 null）
     * @param purpose          调用目的（如 "summarize"），进 detail JSON
     * @param model            实际应答的模型名（调用失败未获响应时为 null）
     * @param durationMs       调用耗时（毫秒）
     * @param promptTokens     输入 token 数（厂商未返回 usage 时为 null）
     * @param completionTokens 输出 token 数（厂商未返回 usage 时为 null）
     * @param error            错误信息（调用成功时为 null）
     */
    default void onAuxiliaryLlmCall(String sessionId, String purpose, String model, long durationMs,
                                    Integer promptTokens, Integer completionTokens, String error) {
        // 默认不观测：需要落库的实现（PersistingAgentInvocationListener）覆写本方法
    }
}
