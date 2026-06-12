package com.sjherp.infra.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.AgentSessionRepository;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.session.SessionStatus;

/**
 * 会话仓储的 MySQL 实现（运行时默认实现）。
 *
 * <p>ADR-001 路线 C 的核心保障：会话的全部状态（含消息历史）落库，
 * 任意时刻杀进程 / 热部署重启后都能凭 {@link AgentSession#restore} 完整恢复。
 *
 * <p>写入约定：
 * <ul>
 *   <li>消息只追加不修改——save 时仅补插 seq 大于库中最大值的新消息；</li>
 *   <li>摘要位点守卫——history_summary / summarized_until_seq 的更新带
 *       {@code WHERE summarized_until_seq <= ?} 守卫，位点只前进不回退
 *       （DB 层兜底守卫，非完整乐观锁；单会话串行处理仍是架构假设）；</li>
 *   <li>时间列为 DATETIME(6)，读写一律按 UTC LocalDateTime 转换，与连接时区解耦。</li>
 * </ul>
 */
@Transactional
public class JdbcAgentSessionRepository implements AgentSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentSessionRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcAgentSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 会话行的中间载体（消息单独查询后再 restore 成聚合） */
    private record SessionRow(String id, String userId, String title, SessionStatus status,
                              String pendingToolCall, String historySummary, int summarizedUntilSeq,
                              Instant createdAt, Instant updatedAt) {
    }

    private static final RowMapper<SessionRow> SESSION_ROW_MAPPER = (rs, rowNum) -> new SessionRow(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("title"),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getString("pending_tool_call"),
            rs.getString("history_summary"),
            rs.getInt("summarized_until_seq"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static final RowMapper<AgentMessage> MESSAGE_ROW_MAPPER = (rs, rowNum) -> new AgentMessage(
            MessageRole.valueOf(rs.getString("role")),
            rs.getString("content"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)));

    @Override
    public void save(AgentSession session) {
        // 先尝试更新，不存在则插入（无并发建会话冲突场景，无需 upsert 语法）
        int updated = jdbc.update(
                "UPDATE agent_session SET title = ?, status = ?, pending_tool_call = ?, "
                        + "updated_at = ? WHERE id = ?",
                session.getTitle(), session.getStatus().name(), session.getPendingToolCallJson(),
                toDb(session.getUpdatedAt()), session.getSessionId());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO agent_session (id, user_id, title, status, pending_tool_call, "
                            + "history_summary, summarized_until_seq, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    session.getSessionId(), session.getUserId(), session.getTitle(),
                    session.getStatus().name(), session.getPendingToolCallJson(),
                    session.getHistorySummary(), session.getSummarizedUntilSeq(),
                    toDb(session.getCreatedAt()), toDb(session.getUpdatedAt()));
        } else {
            // 摘要相关列单独 UPDATE 并带位点守卫（D-8 同批 P2）：summarized_until_seq
            // 只前进不回退——携带旧位点的会话实例（并发/陈旧内存状态）不得覆盖库中已前进的
            // 摘要，否则摘要与位点回退会导致上下文重复或丢失。注意：这是 DB 层守卫而非
            // 完整乐观锁，「单会话串行处理」仍是架构假设（ADR-001），守卫只兜底摘要不回退。
            int summaryUpdated = jdbc.update(
                    "UPDATE agent_session SET history_summary = ?, summarized_until_seq = ? "
                            + "WHERE id = ? AND summarized_until_seq <= ?",
                    session.getHistorySummary(), session.getSummarizedUntilSeq(),
                    session.getSessionId(), session.getSummarizedUntilSeq());
            if (summaryUpdated == 0) {
                log.warn("会话摘要更新被位点守卫拦截（sessionId={}, 传入位点={}）：库中位点更新，"
                                + "已跳过本次摘要写入以防回退（单会话串行假设可能被破坏，需排查）",
                        session.getSessionId(), session.getSummarizedUntilSeq());
            }
        }

        // 消息只追加不修改：补插 seq 大于库中最大值的新消息
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM agent_message WHERE session_id = ?",
                Integer.class, session.getSessionId());
        List<AgentMessage> messages = session.getMessages();
        for (int i = (maxSeq == null ? 0 : maxSeq); i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            jdbc.update(
                    "INSERT INTO agent_message (session_id, seq, role, content, created_at) VALUES (?, ?, ?, ?, ?)",
                    session.getSessionId(), i + 1, message.role().name(),
                    message.content(), toDb(message.createdAt()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentSession> findById(String sessionId) {
        List<SessionRow> rows = jdbc.query(
                "SELECT id, user_id, title, status, pending_tool_call, history_summary, "
                        + "summarized_until_seq, created_at, updated_at "
                        + "FROM agent_session WHERE id = ?",
                SESSION_ROW_MAPPER, sessionId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toAggregate(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentSession> findByUserId(String userId) {
        List<SessionRow> rows = jdbc.query(
                "SELECT id, user_id, title, status, pending_tool_call, history_summary, "
                        + "summarized_until_seq, created_at, updated_at "
                        + "FROM agent_session WHERE user_id = ? ORDER BY updated_at DESC",
                SESSION_ROW_MAPPER, userId);
        return rows.stream().map(this::toAggregate).toList();
    }

    /** 行 -> 聚合：加载消息历史（按 seq 升序）后重建会话 */
    private AgentSession toAggregate(SessionRow row) {
        List<AgentMessage> messages = jdbc.query(
                "SELECT role, content, created_at FROM agent_message WHERE session_id = ? ORDER BY seq",
                MESSAGE_ROW_MAPPER, row.id());
        return AgentSession.restore(row.id(), row.userId(), row.title(), row.status(),
                messages, row.pendingToolCall(), row.historySummary(), row.summarizedUntilSeq(),
                row.createdAt(), row.updatedAt());
    }

    /** Instant -> UTC LocalDateTime（DATETIME(6) 列无时区，统一按 UTC 存） */
    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** UTC LocalDateTime -> Instant */
    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
