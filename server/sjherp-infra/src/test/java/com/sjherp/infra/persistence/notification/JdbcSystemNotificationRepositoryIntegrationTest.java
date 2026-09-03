package com.sjherp.infra.persistence.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sjherp.domain.notification.SystemNotification;
import com.sjherp.domain.notification.SystemNotificationQuery;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

class JdbcSystemNotificationRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcSystemNotificationRepository repository = new JdbcSystemNotificationRepository(jdbc);
    private long adminId;
    private long otherId;

    @BeforeEach
    void setUpRecipients() {
        jdbc.update("DELETE FROM system_notification WHERE tenant_id = 0 AND source_ref LIKE 'IT-T05-%'");
        jdbc.update("DELETE FROM sys_user WHERE tenant_id = 0 AND username LIKE 'it-t05-%'");
        adminId = insertUser("it-t05-admin-" + uniqueSuffix());
        otherId = insertUser("it-t05-other-" + uniqueSuffix());
    }

    @Tag("integration-db")
    @Test
    void isolatesRecipientAndPersistsIdempotentReadTimestamp() {
        SystemNotification notification = notificationFor(adminId);
        repository.save(notification);

        assertThat(repository.countUnread(0, adminId)).isEqualTo(1L);
        assertThat(repository.searchForRecipient(0, otherId,
                new SystemNotificationQuery(1, 20)).items()).isEmpty();

        Instant firstReadAt = Instant.parse("2026-07-19T01:00:00Z");
        notification.markRead(firstReadAt);
        repository.save(notification);
        notification.markRead(firstReadAt.plusSeconds(5));
        repository.save(notification);

        assertThat(repository.findByIdAndRecipient(0, notification.id(), adminId)).get()
                .satisfies(found -> assertThat(found.readAt()).isEqualTo(firstReadAt));
        assertThat(repository.countUnread(0, adminId)).isZero();
    }

    @Tag("integration-db")
    @Test
    void lockingCurrentReadSerializesTwoTransactionsAndReturnsFirstReadTimestamp() throws Exception {
        SystemNotification notification = notificationFor(adminId);
        repository.save(notification);
        Instant firstReadAt = Instant.parse("2026-07-19T01:00:00Z");
        Instant secondReadAt = firstReadAt.plusSeconds(30);
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptingLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<SystemNotification> first = executor.submit(() -> inTransaction(() -> {
                SystemNotification current = lockingFind(notification.id(), adminId);
                firstLocked.countDown();
                current.markRead(firstReadAt);
                repository.save(current);
                await(releaseFirst);
                return current;
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<SystemNotification> second = executor.submit(() -> inTransaction(() -> {
                secondAttemptingLock.countDown();
                SystemNotification current = lockingFind(notification.id(), adminId);
                current.markRead(secondReadAt);
                repository.save(current);
                return current;
            }));
            assertThat(secondAttemptingLock.await(5, TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            SystemNotification firstResult = first.get(5, TimeUnit.SECONDS);
            SystemNotification secondResult = second.get(5, TimeUnit.SECONDS);

            assertThat(firstResult.readAt()).isNotNull().isEqualTo(firstReadAt);
            assertThat(secondResult.readAt()).isNotNull().isEqualTo(firstReadAt);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private SystemNotification lockingFind(long notificationId, long recipientUserId) {
        return repository.findByIdAndRecipientForUpdate(0, notificationId, recipientUserId)
                .orElseThrow();
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> callback) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        return transaction.execute(status -> {
            try {
                return callback.call();
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting to release transaction", exception);
        }
    }

    private long insertUser(String username) {
        jdbc.update("""
                INSERT INTO sys_user (tenant_id, username, display_name, password_hash, roles, status,
                    created_by, created_at, updated_by, updated_at)
                VALUES (0, ?, 'T05 test user', '$2a$10$abcdefghijklmnopqrstuvwxy', '[\"ADMIN\"]', 'ENABLED',
                    'tester', UTC_TIMESTAMP(6), 'tester', UTC_TIMESTAMP(6))
                """, username);
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE tenant_id = 0 AND username = ?",
                Long.class, username);
    }

    private static SystemNotification notificationFor(long recipientUserId) {
        return SystemNotification.create(0, recipientUserId, SystemNotification.Category.CONSISTENCY,
                SystemNotification.Severity.ERROR, "T05 notification", "round trip",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "IT-T05-" + uniqueSuffix(),
                Instant.parse("2026-07-19T00:00:00Z"));
    }
}
