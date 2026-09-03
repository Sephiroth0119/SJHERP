package com.sjherp.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class SystemNotificationTest {

    private static final Instant INSTANT = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void markReadIsIdempotentAndCannotChangeRecipient() {
        SystemNotification notification = SystemNotification.create(0, 7,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                "一致性检查异常", "运行 CHK-202607-0001 发现 1 项错误",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "CHK-202607-0001", INSTANT);
        notification.markRead(INSTANT.plusSeconds(1));
        notification.markRead(INSTANT.plusSeconds(2));

        assertThat(notification.readAt()).isEqualTo(INSTANT.plusSeconds(1));
        assertThat(notification.recipientUserId()).isEqualTo(7);
    }

    @Test
    void validatesRecipientTitleContentAndSourceReference() {
        assertThatThrownBy(() -> SystemNotification.create(0, 0,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                "标题", "内容", SystemNotification.SourceType.CONSISTENCY_REPORT,
                "CHK-1", INSTANT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("接收人");
        assertThatThrownBy(() -> SystemNotification.create(0, 7,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                " ", "内容", SystemNotification.SourceType.CONSISTENCY_REPORT,
                "CHK-1", INSTANT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标题");
        assertThatThrownBy(() -> new SystemNotificationQuery(1, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("分页参数不合法");
    }

    @Test
    void assignedIdIsAuditableAndSummaryDoesNotExposeContent() {
        SystemNotification notification = SystemNotification.create(0, 7,
                SystemNotification.Category.CONSISTENCY, SystemNotification.Severity.ERROR,
                "一致性检查异常", "运行 CHK-202607-0001 发现 1 项错误",
                SystemNotification.SourceType.CONSISTENCY_REPORT, "CHK-202607-0001", INSTANT);

        notification.assignId(8);

        assertThat(notification.auditTargetId()).isEqualTo(8);
        assertThat(notification.auditTargetCode()).isEqualTo("CHK-202607-0001");
        assertThat(notification.auditSummary()).doesNotContain("运行 CHK-202607-0001 发现 1 项错误");
        assertThatThrownBy(() -> notification.assignId(9)).isInstanceOf(IllegalStateException.class);
    }
}
