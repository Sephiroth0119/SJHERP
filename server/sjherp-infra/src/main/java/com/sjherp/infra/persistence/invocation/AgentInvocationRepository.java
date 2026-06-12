package com.sjherp.infra.persistence.invocation;

import com.sjherp.domain.common.PageResult;

/**
 * Agent 调用观测记录仓储（M1-T06）。只插入与查询，不提供更新/删除（可审计）。
 */
public interface AgentInvocationRepository {

    /** 追加一条调用记录 */
    void insert(AgentInvocation invocation);

    /** 按会话分页查询（created_at 倒序，最新在前） */
    PageResult<AgentInvocation> findBySession(String sessionId, int page, int size);

    /** 会话累计 token 汇总（仅统计 LLM 记录；usage 缺失的行按 0 计） */
    TokenSummary sumTokens(String sessionId);

    /**
     * 会话累计 token 汇总。
     *
     * @param totalPromptTokens     累计输入 token 数
     * @param totalCompletionTokens 累计输出 token 数
     */
    record TokenSummary(long totalPromptTokens, long totalCompletionTokens) {
    }
}
