package com.sjherp.app.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sjherp.domain.consistency.ConsistencyCheckRun;
import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;
import com.sjherp.domain.notification.SystemNotification;
import com.sjherp.domain.notification.SystemNotificationRepository;

/** 向启用中的管理员和老板发送不含差异正文的站内摘要。 */
@Service
public class InAppNotificationChannel implements NotificationChannel {

    private static final Set<Role> RECIPIENT_ROLES = Set.of(Role.ADMIN, Role.BOSS);

    private final UserRepository users;
    private final SystemNotificationRepository notifications;
    private final Clock clock;

    @Autowired
    public InAppNotificationChannel(UserRepository users,
                                    SystemNotificationRepository notifications) {
        this(users, notifications, Clock.systemUTC());
    }

    InAppNotificationChannel(UserRepository users,
                             SystemNotificationRepository notifications,
                             Clock clock) {
        this.users = Objects.requireNonNull(users, "users 不能为空");
        this.notifications = Objects.requireNonNull(notifications, "notifications 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public void send(ConsistencyCheckRun run) {
        Objects.requireNonNull(run, "run 不能为空");
        if (run.status() == ConsistencyCheckRun.Status.COMPLETED && run.clean()) {
            return;
        }
        for (User user : users.findAll()) {
            if (!eligible(user)) {
                continue;
            }
            long recipientId = user.getId();
            if (notifications.existsBySource(run.tenantId(), recipientId,
                    SystemNotification.SourceType.CONSISTENCY_REPORT, run.runNo())) {
                continue;
            }
            notifications.save(toNotification(run, recipientId));
        }
    }

    private static boolean eligible(User user) {
        return user != null && user.getId() != null && user.isEnabled()
                && user.getRoles().stream().anyMatch(RECIPIENT_ROLES::contains);
    }

    private SystemNotification toNotification(ConsistencyCheckRun run, long recipientId) {
        return SystemNotification.create(run.tenantId(), recipientId,
                SystemNotification.Category.CONSISTENCY, severity(run), title(run), content(run),
                SystemNotification.SourceType.CONSISTENCY_REPORT, run.runNo(), Instant.now(clock));
    }

    private static SystemNotification.Severity severity(ConsistencyCheckRun run) {
        if (run.status() == ConsistencyCheckRun.Status.FAILED || run.errorCount() > 0) {
            return SystemNotification.Severity.ERROR;
        }
        if (run.warnCount() > 0) {
            return SystemNotification.Severity.WARN;
        }
        return SystemNotification.Severity.INFO;
    }

    private static String title(ConsistencyCheckRun run) {
        return run.status() == ConsistencyCheckRun.Status.FAILED
                ? "一致性检查运行失败" : "一致性检查发现差异";
    }

    private static String content(ConsistencyCheckRun run) {
        return "运行编号=" + run.runNo() + ", 来源=" + run.triggerType()
                + ", 总数=" + run.totalCount() + ", 错误=" + run.errorCount()
                + ", 警告=" + run.warnCount() + ", 提示=" + run.infoCount();
    }
}
