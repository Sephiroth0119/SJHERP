package com.sjherp.infra.persistence.notification;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.notification.SystemNotification;
import com.sjherp.domain.notification.SystemNotificationQuery;
import com.sjherp.domain.notification.SystemNotificationRepository;

/** MySQL implementation of recipient-scoped system notifications. */
@Transactional
public class JdbcSystemNotificationRepository implements SystemNotificationRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, recipient_user_id, category, severity, title, content,
                   source_type, source_ref, read_at, created_at
              FROM system_notification
            """;

    private static final RowMapper<SystemNotification> ROW_MAPPER = (rs, rowNum) ->
            SystemNotification.restore(rs.getLong("id"), rs.getLong("tenant_id"),
                    rs.getLong("recipient_user_id"),
                    SystemNotification.Category.valueOf(rs.getString("category")),
                    SystemNotification.Severity.valueOf(rs.getString("severity")),
                    rs.getString("title"), rs.getString("content"),
                    SystemNotification.SourceType.valueOf(rs.getString("source_type")),
                    rs.getString("source_ref"), fromDbNullable(rs.getObject("read_at", LocalDateTime.class)),
                    fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcSystemNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void save(SystemNotification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        if (notification.id() == null) {
            notification.assignId(insert(notification));
        } else {
            updateReadAt(notification);
        }
    }

    @Override
    public boolean saveIfAbsent(SystemNotification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        try { jdbc.update("""
                INSERT IGNORE INTO system_notification (
                    tenant_id, recipient_user_id, category, severity, title, content,
                    source_type, source_ref, read_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, notification.tenantId(), notification.recipientUserId(),
                notification.category().name(), notification.severity().name(), notification.title(),
                notification.content(), notification.sourceType().name(), notification.sourceRef(),
                toDbNullable(notification.readAt()), toDb(notification.createdAt())); return true; }
        catch (DuplicateKeyException duplicate) { return false; }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SystemNotification> searchForRecipient(long tenantId, long recipientUserId,
                                                               SystemNotificationQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return new PageResult<>(findInbox(tenantId, recipientUserId, query),
                countInbox(tenantId, recipientUserId), query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(long tenantId, long recipientUserId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_notification
                 WHERE tenant_id = ? AND recipient_user_id = ? AND read_at IS NULL
                """, Long.class, tenantId, recipientUserId);
        return total == null ? 0L : total;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SystemNotification> findByIdAndRecipient(long tenantId, long id, long recipientUserId) {
        return first(jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = ? AND id = ? AND recipient_user_id = ?
                """, ROW_MAPPER, tenantId, id, recipientUserId));
    }

    @Override
    @Transactional
    public Optional<SystemNotification> findByIdAndRecipientForUpdate(long tenantId, long id,
                                                                       long recipientUserId) {
        return first(jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = ? AND id = ? AND recipient_user_id = ?
                FOR UPDATE
                """, ROW_MAPPER, tenantId, id, recipientUserId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsBySource(long tenantId, long recipientUserId,
                                  SystemNotification.SourceType sourceType, String sourceRef) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_notification
                 WHERE tenant_id = ? AND recipient_user_id = ? AND source_type = ? AND source_ref = ?
                """, Long.class, tenantId, recipientUserId, sourceType.name(), sourceRef);
        return count != null && count > 0L;
    }

    private long insert(SystemNotification notification) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO system_notification (
                        tenant_id, recipient_user_id, category, severity, title, content,
                        source_type, source_ref, read_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            statement.setLong(index++, notification.tenantId());
            statement.setLong(index++, notification.recipientUserId());
            statement.setString(index++, notification.category().name());
            statement.setString(index++, notification.severity().name());
            statement.setString(index++, notification.title());
            statement.setString(index++, notification.content());
            statement.setString(index++, notification.sourceType().name());
            statement.setString(index++, notification.sourceRef());
            statement.setObject(index++, toDbNullable(notification.readAt()));
            statement.setObject(index, toDb(notification.createdAt()));
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey(), "未取得通知自增主键").longValue();
    }

    private void updateReadAt(SystemNotification notification) {
        int affected = jdbc.update("""
                UPDATE system_notification
                   SET read_at = COALESCE(read_at, ?)
                 WHERE tenant_id = ? AND id = ? AND recipient_user_id = ?
                """, toDbNullable(notification.readAt()), notification.tenantId(), notification.id(),
                notification.recipientUserId());
        if (affected != 1) {
            throw new IllegalStateException("通知不存在或接收人不匹配: " + notification.id());
        }
    }

    private List<SystemNotification> findInbox(long tenantId, long recipientUserId,
                                                 SystemNotificationQuery query) {
        long offset = Math.multiplyExact((long) (query.page() - 1), query.size());
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = ? AND recipient_user_id = ?
                ORDER BY id DESC LIMIT ? OFFSET ?
                """, ROW_MAPPER, tenantId, recipientUserId, query.size(), offset);
    }

    private long countInbox(long tenantId, long recipientUserId) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM system_notification
                 WHERE tenant_id = ? AND recipient_user_id = ?
                """, Long.class, tenantId, recipientUserId);
        return total == null ? 0L : total;
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static LocalDateTime toDbNullable(Instant instant) {
        return instant == null ? null : toDb(instant);
    }

    private static Instant fromDb(LocalDateTime value) {
        return Objects.requireNonNull(value, "database timestamp must not be null").toInstant(ZoneOffset.UTC);
    }

    private static Instant fromDbNullable(LocalDateTime value) {
        return value == null ? null : fromDb(value);
    }
}
