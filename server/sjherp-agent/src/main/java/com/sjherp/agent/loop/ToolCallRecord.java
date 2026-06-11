package com.sjherp.agent.loop;

/**
 * 执行循环中一次工具调用的记录（可观测性：名称 / 参数 / 结果 / 耗时）。
 *
 * <p>由 {@link AgentLoop} 在每次实际处理工具调用时追加（含未知工具、参数校验失败、
 * 执行异常、用户取消等失败情形），随 {@link AgentLoopResult} 整体返回，供上层
 * 记日志 / 落库（M1-T06 agent_invocation 表的前置数据源）。
 *
 * @param toolCallId    厂商分配的调用 id
 * @param toolName      工具名称
 * @param argumentsJson 模型给出的原始参数 JSON
 * @param resultContent 回灌给模型的结果文本（成功为结果 JSON，失败为错误 JSON）
 * @param success       工具是否成功执行（未知工具 / 校验失败 / 异常 / 取消均为 false）
 * @param elapsedMillis 执行耗时（毫秒；未实际执行的情形为 0）
 */
public record ToolCallRecord(String toolCallId, String toolName, String argumentsJson,
                             String resultContent, boolean success, long elapsedMillis) {
}
