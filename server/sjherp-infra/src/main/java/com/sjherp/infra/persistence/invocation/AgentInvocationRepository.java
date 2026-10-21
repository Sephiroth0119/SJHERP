package com.sjherp.infra.persistence.invocation;

import java.time.Instant;
import java.util.List;

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
     * 时间窗内 LLM 调用汇总（M8-T04 日报）。只读聚合，不含金额。
     *
     * @param fromInclusive 起（含），UTC
     * @param toExclusive   止（不含），UTC
     */
    LlmUsageWindow summarizeLlm(Instant fromInclusive, Instant toExclusive);

    /**
     * 会话累计 token 汇总。
     *
     * @param totalPromptTokens     累计输入 token 数
     * @param totalCompletionTokens 累计输出 token 数
     */
    record TokenSummary(long totalPromptTokens, long totalCompletionTokens) {
    }

    /**
     * 时间窗 LLM 用量（按模型拆行）。usage 缺失的 token 按 0；model 空归为 {@code (unknown)}。
     */
    record LlmUsageWindow(Instant fromInclusive, Instant toExclusive,
                          long llmCalls, long llmFailures,
                          long promptTokens, long completionTokens, long durationMs,
                          List<ModelBreakdown> byModel) {
        public LlmUsageWindow {
            byModel = byModel == null ? List.of() : List.copyOf(byModel);
        }
    }

    record ModelBreakdown(String model, long calls, long failures,
                          long promptTokens, long completionTokens, long durationMs) {
    }
}
