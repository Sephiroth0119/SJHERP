package com.sjherp.infra.persistence.invocation;

import java.time.Instant;

/**
 * Agent 调用观测记录（agent_invocation 表的一行，M1-T06）。
 *
 * <p>每次 LLM 调用与每次工具调用各产生一行，只插入不更新（可审计）。
 * 不属于业务领域模型，故直接放 infra（与会话仓储同理由：框架运行数据）。
 *
 * @param id               主键（插入前为 null）
 * @param sessionId        会话 id（无审计上下文时可为 null）
 * @param type             调用类型（LLM / TOOL）
 * @param model            模型名（type=LLM；调用失败未获响应时为 null）
 * @param toolName         工具名（type=TOOL）
 * @param durationMs       耗时（毫秒）
 * @param promptTokens     输入 token 数（type=LLM；厂商未返回 usage 时为 null）
 * @param completionTokens 输出 token 数（type=LLM；厂商未返回 usage 时为 null）
 * @param success          是否成功（LLM：调用未抛错；TOOL：工具成功执行）
 * @param detailJson       明细 JSON（LLM：round/hasToolCalls/error；TOOL：arguments/resultSummary/riskLevel/confirmed）
 * @param createdAt        创建时间（UTC）
 */
public record AgentInvocation(Long id, String sessionId, AgentInvocationType type,
                              String model, String toolName, long durationMs,
                              Integer promptTokens, Integer completionTokens,
                              boolean success, String detailJson, Instant createdAt) {
}
