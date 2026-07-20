package com.sjherp.infra.persistence.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;

import com.sjherp.domain.notification.SystemNotification;
import com.sjherp.domain.notification.SystemNotificationQuery;

class JdbcSystemNotificationRepositoryTest {

    @Test
    void updatePreservesFirstPersistedReadTimestampWithCoalesceAndOwnershipScope() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        Instant readAt = Instant.parse("2026-07-19T01:00:00Z");
        SystemNotification notification = notification(99, 7, readAt);

        new JdbcSystemNotificationRepository(jdbc).save(notification);

        assertThat(jdbc.updateSql).contains("SET read_at = COALESCE(read_at, ?)");
        assertThat(jdbc.updateArguments).containsExactly(
                LocalDateTime.parse("2026-07-19T01:00:00"), 0L, 99L, 7L);
    }

    @Test
    void lockingLookupUsesCurrentReadWithTenantAndRecipientScope() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();

        new JdbcSystemNotificationRepository(jdbc).findByIdAndRecipientForUpdate(0, 99, 7);

        assertThat(jdbc.querySql)
                .contains("WHERE tenant_id = ? AND id = ? AND recipient_user_id = ?")
                .contains("FOR UPDATE");
        assertThat(jdbc.queryArguments).containsExactly(0L, 99L, 7L);
    }

    @Test
    void largePageUsesPositiveLongOffset() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();

        new JdbcSystemNotificationRepository(jdbc).searchForRecipient(
                0, 7, new SystemNotificationQuery(Integer.MAX_VALUE, 100));

        assertThat(jdbc.queryArguments)
                .containsExactly(0L, 7L, 100, 214_748_364_600L);
        assertThat(jdbc.queryArguments[3]).isInstanceOf(Long.class);
    }

    @Test
    void saveIfAbsentUsesStrictInsertWithoutIgnore() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();

        assertThat(new JdbcSystemNotificationRepository(jdbc).saveIfAbsent(newNotification(7))).isTrue();

        assertThat(jdbc.updateSql).contains("INSERT INTO system_notification")
                .doesNotContainIgnoringCase("INSERT IGNORE");
    }

    @Test
    void saveIfAbsentTurnsOnlyDuplicateKeyIntoFalse() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        jdbc.failure = new DuplicateKeyException("duplicate source");

        assertThat(new JdbcSystemNotificationRepository(jdbc).saveIfAbsent(newNotification(7))).isFalse();
    }

    @Test
    void saveIfAbsentPropagatesNonDuplicateDatabaseFailures() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        IllegalStateException failure = new IllegalStateException("foreign key failure");
        jdbc.failure = failure;

        assertThatThrownBy(() -> new JdbcSystemNotificationRepository(jdbc)
                .saveIfAbsent(newNotification(7)))
                .isSameAs(failure);
    }

    private static SystemNotification notification(long id, long recipientId, Instant readAt) {
        return SystemNotification.restore(id, 0, recipientId,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                "一致性检查异常", "safe content",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "CHK-1", readAt,
                Instant.parse("2026-07-19T00:00:00Z"));
    }

    private static SystemNotification newNotification(long recipientId) {
        return SystemNotification.create(0, recipientId, SystemNotification.Category.CONSISTENCY,
                SystemNotification.Severity.ERROR, "test notification", "content",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "source-test",
                Instant.parse("2026-07-19T00:00:00Z"));
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String updateSql;
        private String querySql;
        private Object[] updateArguments;
        private Object[] queryArguments;
        private RuntimeException failure;

        @Override
        public int update(String sql, Object... args) {
            if (failure != null) throw failure;
            updateSql = sql;
            updateArguments = args;
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(0L);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            querySql = sql;
            queryArguments = args;
            return List.of();
        }
    }
}
