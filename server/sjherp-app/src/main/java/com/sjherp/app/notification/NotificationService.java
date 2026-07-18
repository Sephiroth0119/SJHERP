package com.sjherp.app.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.notification.SystemNotification;
import com.sjherp.domain.notification.SystemNotificationQuery;
import com.sjherp.domain.notification.SystemNotificationRepository;

/** 当前接收人范围内的通知查询与标记已读服务，不提供删除能力。 */
@Service
public class NotificationService {

    private static final long TENANT_ID = 0L;

    private final SystemNotificationRepository repository;
    private final Clock clock;

    @Autowired
    public NotificationService(SystemNotificationRepository repository) {
        this(repository, Clock.systemUTC());
    }

    NotificationService(SystemNotificationRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Transactional(readOnly = true)
    public PageResult<SystemNotification> search(long userId, int page, int size) {
        return repository.searchForRecipient(TENANT_ID, requireUserId(userId),
                new SystemNotificationQuery(page, size));
    }

    @Transactional(readOnly = true)
    public long countUnread(long userId) {
        return repository.countUnread(TENANT_ID, requireUserId(userId));
    }

    @Transactional
    public SystemNotification markRead(long userId, long id) {
        long recipientId = requireUserId(userId);
        if (id < 1) {
            throw new NotificationNotFoundException(id);
        }
        SystemNotification notification = repository.findByIdAndRecipientForUpdate(TENANT_ID, id, recipientId)
                .orElseThrow(() -> new NotificationNotFoundException(id));
        notification.markRead(Instant.now(clock));
        repository.save(notification);
        return notification;
    }

    private static long requireUserId(long userId) {
        if (userId < 1) {
            throw new IllegalArgumentException("用户 id 必须为正数");
        }
        return userId;
    }
}
