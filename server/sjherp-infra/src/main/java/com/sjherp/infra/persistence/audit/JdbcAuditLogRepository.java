package com.sjherp.infra.persistence.audit;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;

/**
 * 审计日志仓储的 MySQL 实现（V9 迁移 audit_log 表）。
 *
 * <p>时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentInvocationRepository）。
 * 只插入与查询：审计记录不可修改/删除。
 *
 * <p>插入使用 REQUIRES_NEW 独立事务（D-8 后角色调整）：调用方（app 层
 * TransactionAwareAuditWriter）已做事务感知——有外层业务事务时延迟到 afterCommit
 * 回调中才调用本方法。afterCommit 中原连接虽已提交但事务资源仍绑定线程，
 * Spring 约定此处的数据访问应开新事务，REQUIRES_NEW 正是该约定的落点；
 * 无事务路径下它就是原来的独立事务语义。失败兜底在 writer 侧 WARN + 计数。
 */
@Transactional
public class JdbcAuditLogRepository implements AuditLogRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, operator, action, target_type, target_id, target_code, summary, "
                    + "session_id, created_at FROM audit_log ";

    private static final RowMapper<AuditLogEntry> ROW_MAPPER = (rs, rowNum) -> new AuditLogEntry(
            rs.getLong("id"),
            rs.getString("operator"),
            rs.getString("action"),
            rs.getString("target_type"),
            (Long) rs.getObject("target_id"),
            rs.getString("target_code"),
            rs.getString("summary"),
            rs.getString("session_id"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcAuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AuditLogEntry entry) {
        jdbc.update("INSERT INTO audit_log (operator, action, target_type, target_id, "
                        + "target_code, summary, session_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                entry.operator(),
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                entry.targetCode(),
                entry.summary(),
                entry.sessionId(),
                toDb(entry.createdAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditLogEntry> search(AuditLogQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (query.operator() != null && !query.operator().isBlank()) {
            where.append("AND operator = ? ");
            args.add(query.operator().strip());
        }
        if (query.action() != null && !query.action().isBlank()) {
            where.append("AND action = ? ");
            args.add(query.action().strip());
        }
        if (query.targetType() != null && !query.targetType().isBlank()) {
            where.append("AND target_type = ? ");
            args.add(query.targetType().strip());
        }
        if (query.targetId() != null) {
            where.append("AND target_id = ? ");
            args.add(query.targetId());
        }
        if (query.from() != null) {
            where.append("AND created_at >= ? ");
            args.add(toDb(query.from()));
        }
        if (query.to() != null) {
            where.append("AND created_at <= ? ");
            args.add(toDb(query.to()));
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        // 时间倒序（最新在前）；created_at 同毫秒时按 id 倒序保证顺序稳定
        args.add(query.size());
        args.add((long) (query.page() - 1) * query.size());
        List<AuditLogEntry> rows = jdbc.query(
                SELECT_COLUMNS + where + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, args.toArray());
        return new PageResult<>(rows, totalCount, query.page(), query.size());
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
