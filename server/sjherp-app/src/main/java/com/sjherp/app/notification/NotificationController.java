package com.sjherp.app.notification;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sjherp.app.security.CurrentUser;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.notification.SystemNotification;

/** 当前登录用户的个人站内通知 API；不接受客户端指定接收人。 */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = Objects.requireNonNull(notificationService,
                "notificationService 不能为空");
    }

    @GetMapping
    public NotificationPageResponse search(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return NotificationPageResponse.from(
                notificationService.search(currentRecipientId(), page, size));
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(notificationService.countUnread(currentRecipientId()));
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable long id) {
        return NotificationResponse.from(notificationService.markRead(currentRecipientId(), id));
    }

    private static long currentRecipientId() {
        return Long.parseLong(CurrentUser.userId());
    }

    public record NotificationPageResponse(List<NotificationResponse> items, long total,
                                           int page, int size) {

        static NotificationPageResponse from(PageResult<SystemNotification> result) {
            return new NotificationPageResponse(
                    result.items().stream().map(NotificationResponse::from).toList(),
                    result.total(), result.page(), result.size());
        }
    }

    public record UnreadCountResponse(long unreadCount) {}

    public record NotificationResponse(long id, String category, String severity, String title,
                                       String content, String sourceType, String sourceRef,
                                       boolean read, Instant readAt, Instant createdAt) {

        static NotificationResponse from(SystemNotification notification) {
            return new NotificationResponse(notification.id(), notification.category().name(),
                    notification.severity().name(), notification.title(), notification.content(),
                    notification.sourceType().name(), notification.sourceRef(),
                    notification.readAt() != null, notification.readAt(), notification.createdAt());
        }
    }
}
