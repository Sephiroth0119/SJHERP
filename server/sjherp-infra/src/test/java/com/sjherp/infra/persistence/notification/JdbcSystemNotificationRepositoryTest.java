package com.sjherp.infra.persistence.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
    void largePageUsesPositiveLongOffset() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();

        new JdbcSystemNotificationRepository(jdbc).searchForRecipient(
                0, 7, new SystemNotificationQuery(Integer.MAX_VALUE, 100));

        assertThat(jdbc.queryArguments)
                .containsExactly(0L, 7L, 100, 214_748_364_600L);
        assertThat(jdbc.queryArguments[3]).isInstanceOf(Long.class);
    }

    private static SystemNotification notification(long id, long recipientId, Instant readAt) {
        return SystemNotification.restore(id, 0, recipientId,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                "一致性检查异常", "safe content",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "CHK-1", readAt,
                Instant.parse("2026-07-19T00:00:00Z"));
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String updateSql;
        private Object[] updateArguments;
        private Object[] queryArguments;

        @Override
        public int update(String sql, Object... args) {
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
            queryArguments = args;
            return List.of();
        }
    }
}
