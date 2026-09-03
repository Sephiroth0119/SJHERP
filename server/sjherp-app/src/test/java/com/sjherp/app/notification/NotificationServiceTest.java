package com.sjherp.app.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.notification.SystemNotification;
import com.sjherp.domain.notification.SystemNotificationQuery;
import com.sjherp.domain.notification.SystemNotificationRepository;

class NotificationServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant READ_AT = Instant.parse("2026-07-19T00:00:00Z");
    private final SystemNotificationRepository repository = mock(SystemNotificationRepository.class);
    private final NotificationService service = new NotificationService(
            repository, Clock.fixed(READ_AT, ZoneOffset.UTC));

    @Test
    void searchesAndCountsOnlyTenantZeroAndRequestedRecipient() {
        PageResult<SystemNotification> expected = new PageResult<>(List.of(), 0, 2, 20);
        when(repository.searchForRecipient(0, 7, new SystemNotificationQuery(2, 20)))
                .thenReturn(expected);
        when(repository.countUnread(0, 7)).thenReturn(3L);

        assertThat(service.search(7, 2, 20)).isSameAs(expected);
        assertThat(service.countUnread(7)).isEqualTo(3);
        verify(repository).searchForRecipient(0, 7, new SystemNotificationQuery(2, 20));
        verify(repository).countUnread(0, 7);
    }

    @Test
    void rejectsInvalidPagingBeforeRepositoryCall() {
        assertThatThrownBy(() -> service.search(7, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(7, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(7, 1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markReadScopesLookupToOwnerAndPersistsAggregate() {
        SystemNotification notification = unreadNotification(99, 7);
        when(repository.findByIdAndRecipientForUpdate(0, 99, 7)).thenReturn(Optional.of(notification));

        SystemNotification updated = service.markRead(7, 99);

        assertThat(updated).isSameAs(notification);
        assertThat(updated.readAt()).isEqualTo(READ_AT);
        verify(repository).findByIdAndRecipientForUpdate(0, 99, 7);
        verify(repository, never()).findByIdAndRecipient(0, 99, 7);
        verify(repository).save(notification);
    }

    @Test
    void foreignOrMissingNotificationUsesDedicatedNotFoundAndDoesNotSave() {
        when(repository.findByIdAndRecipientForUpdate(0, 99, 8)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(8, 99))
                .isInstanceOf(NotificationNotFoundException.class)
                .hasMessageContaining("99");
        verify(repository).findByIdAndRecipientForUpdate(0, 99, 8);
        verify(repository, never()).findByIdAndRecipient(0, 99, 8);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lockingCurrentReadReturnsFirstPersistedTimestamp() {
        Instant firstPersistedReadAt = READ_AT.minusSeconds(30);
        SystemNotification current = readNotification(99, 7, firstPersistedReadAt);
        when(repository.findByIdAndRecipientForUpdate(0, 99, 7)).thenReturn(Optional.of(current));

        SystemNotification updated = service.markRead(7, 99);

        assertThat(updated).isSameAs(current);
        assertThat(updated.readAt()).isEqualTo(firstPersistedReadAt);
        verify(repository).save(current);
        verify(repository).findByIdAndRecipientForUpdate(0, 99, 7);
        verify(repository, never()).findByIdAndRecipient(0, 99, 7);
    }

    @Test
    void readMethodsAreReadOnlyMarkReadIsTransactionalAndNoDeleteIsExposed() throws Exception {
        assertThat(NotificationService.class.getMethod("search", long.class, int.class, int.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(NotificationService.class.getMethod("countUnread", long.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        Transactional markRead = NotificationService.class
                .getMethod("markRead", long.class, long.class).getAnnotation(Transactional.class);
        assertThat(markRead).isNotNull();
        assertThat(markRead.readOnly()).isFalse();
        assertThat(Arrays.stream(NotificationService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)).noneMatch(name -> name.toLowerCase().contains("delete"));
    }

    private static SystemNotification unreadNotification(long id, long recipientId) {
        return readNotification(id, recipientId, null);
    }

    private static SystemNotification readNotification(long id, long recipientId, Instant readAt) {
        return SystemNotification.restore(id, 0, recipientId,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                "一致性检查异常", "运行编号=CHK-1，来源=MANUAL_API，总数=1，错误=1，警告=0，提示=0",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "CHK-1", readAt, CREATED_AT);
    }
}
