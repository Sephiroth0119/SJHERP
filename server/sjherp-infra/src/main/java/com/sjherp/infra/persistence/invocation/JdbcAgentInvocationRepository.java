package com.sjherp.infra.persistence.invocation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;

/**
 * Agent 调用观测记录仓储的 MySQL 实现（V7 迁移 agent_invocation 表）。
 *
 * <p>时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcGapRecordRepository）。
 * 只插入与查询：观测记录是审计数据，不可修改/删除。
 */
@Transactional
public class JdbcAgentInvocationRepository implements AgentInvocationRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, session_id, type, model, tool_name, duration_ms, "
                    + "prompt_tokens, completion_tokens, success, detail, created_at FROM agent_invocation ";

    private static final RowMapper<AgentInvocation> ROW_MAPPER = (rs, rowNum) -> new AgentInvocation(
            rs.getLong("id"),
            rs.getString("session_id"),
            AgentInvocationType.valueOf(rs.getString("type")),
            rs.getString("model"),
            rs.getString("tool_name"),
            rs.getLong("duration_ms"),
            (Integer) rs.getObject("prompt_tokens"),
            (Integer) rs.getObject("completion_tokens"),
            rs.getBoolean("success"),
            rs.getString("detail"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcAgentInvocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AgentInvocation invocation) {
        jdbc.update("INSERT INTO agent_invocation (session_id, type, model, tool_name, duration_ms, "
                        + "prompt_tokens, completion_tokens, success, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                invocation.sessionId(),
                invocation.type().name(),
                invocation.model(),
                invocation.toolName(),
                invocation.durationMs(),
                invocation.promptTokens(),
                invocation.completionTokens(),
                invocation.success(),
                invocation.detailJson(),
                toDb(invocation.createdAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AgentInvocation> findBySession(String sessionId, int page, int size) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_invocation WHERE session_id = ?", Long.class, sessionId);
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, page, size);
        }
        // 时间倒序（最新在前）；created_at 同毫秒时按 id 倒序保证顺序稳定
        List<AgentInvocation> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, sessionId, size, (long) (page - 1) * size);
        return new PageResult<>(rows, totalCount, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public TokenSummary sumTokens(String sessionId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(prompt_tokens), 0) AS p, COALESCE(SUM(completion_tokens), 0) AS c "
                        + "FROM agent_invocation WHERE session_id = ? AND type = 'LLM'",
                (rs, rowNum) -> new TokenSummary(rs.getLong("p"), rs.getLong("c")),
                sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public LlmUsageWindow summarizeLlm(Instant fromInclusive, Instant toExclusive) {
        if (fromInclusive == null || toExclusive == null) {
            throw new IllegalArgumentException("LLM 汇总时间窗不能为空");
        }
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("LLM 汇总时间窗起点必须早于终点");
        }
        List<ModelBreakdown> byModel = jdbc.query(
                "SELECT COALESCE(NULLIF(model, ''), '(unknown)') AS model, "
                        + "COUNT(*) AS calls, "
                        + "SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failures, "
                        + "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens, "
                        + "COALESCE(SUM(completion_tokens), 0) AS completion_tokens, "
                        + "COALESCE(SUM(duration_ms), 0) AS duration_ms "
                        + "FROM agent_invocation "
                        + "WHERE type = 'LLM' AND created_at >= ? AND created_at < ? "
                        + "GROUP BY COALESCE(NULLIF(model, ''), '(unknown)') "
                        + "ORDER BY calls DESC, model ASC",
                (rs, rowNum) -> new ModelBreakdown(
                        rs.getString("model"),
                        rs.getLong("calls"),
                        rs.getLong("failures"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        rs.getLong("duration_ms")),
                toDb(fromInclusive), toDb(toExclusive));
        long calls = 0;
        long failures = 0;
        long prompt = 0;
        long completion = 0;
        long duration = 0;
        for (ModelBreakdown row : byModel) {
            calls += row.calls();
            failures += row.failures();
            prompt += row.promptTokens();
            completion += row.completionTokens();
            duration += row.durationMs();
        }
        return new LlmUsageWindow(fromInclusive, toExclusive, calls, failures,
                prompt, completion, duration, byModel);
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
