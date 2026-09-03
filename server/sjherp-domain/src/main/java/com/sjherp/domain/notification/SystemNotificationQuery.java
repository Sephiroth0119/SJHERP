package com.sjherp.domain.notification;

/** 个人站内通知分页查询参数。 */
public record SystemNotificationQuery(int page, int size) {

    public SystemNotificationQuery {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("分页参数不合法");
        }
    }
}
