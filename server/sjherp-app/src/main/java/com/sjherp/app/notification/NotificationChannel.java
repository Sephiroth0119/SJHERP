package com.sjherp.app.notification;

import com.sjherp.domain.consistency.ConsistencyCheckRun;

/** 一致性运行结果通知通道。T05 仅实现站内通道。 */
public interface NotificationChannel {

    void send(ConsistencyCheckRun run);
}
