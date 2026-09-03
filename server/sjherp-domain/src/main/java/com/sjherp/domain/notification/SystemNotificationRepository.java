package com.sjherp.domain.notification;

import java.util.Optional;

import com.sjherp.domain.common.PageResult;

/** 站内通知持久化端口；只支持保存及接收人范围内查询。 */
public interface SystemNotificationRepository {

    void save(SystemNotification notification);

    /** Atomically persists a new notification, returning false when its source was already claimed. */
    boolean saveIfAbsent(SystemNotification notification);

    PageResult<SystemNotification> searchForRecipient(long tenantId, long recipientUserId,
                                                       SystemNotificationQuery query);

    long countUnread(long tenantId, long recipientUserId);

    Optional<SystemNotification> findByIdAndRecipient(long tenantId, long id, long recipientUserId);

    Optional<SystemNotification> findByIdAndRecipientForUpdate(long tenantId, long id,
                                                                long recipientUserId);

    boolean existsBySource(long tenantId, long recipientUserId,
                           SystemNotification.SourceType sourceType, String sourceRef);
}
