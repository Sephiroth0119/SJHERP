package com.sjherp.app.notification;

/** 通知不存在或不属于当前接收人；统一按不存在处理以避免越权探测。 */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(long id) {
        super("通知不存在: id=" + id);
    }
}
