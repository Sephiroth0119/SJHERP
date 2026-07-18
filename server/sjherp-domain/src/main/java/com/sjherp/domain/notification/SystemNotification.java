package com.sjherp.domain.notification;

import java.time.Instant;
import java.util.Objects;

import com.sjherp.domain.common.audit.AuditTarget;

/** 接收人范围内唯一、仅支持标记已读的站内通知聚合。 */
public final class SystemNotification implements AuditTarget {

    public enum Category { CONSISTENCY }

    public enum Severity { ERROR, WARN, INFO }

    public enum SourceType { CONSISTENCY_REPORT }

    private Long id;
    private final long tenantId;
    private final long recipientUserId;
    private final Category category;
    private final Severity severity;
    private final String title;
    private final String content;
    private final SourceType sourceType;
    private final String sourceRef;
    private Instant readAt;
    private final Instant createdAt;

    private SystemNotification(Long id, long tenantId, long recipientUserId, Category category,
                               Severity severity, String title, String content, SourceType sourceType,
                               String sourceRef, Instant readAt, Instant createdAt) {
        this.id = id;
        this.tenantId = requireTenantId(tenantId);
        if (recipientUserId < 1) {
            throw new IllegalArgumentException("接收人 id 必须为正数");
        }
        this.recipientUserId = recipientUserId;
        this.category = Objects.requireNonNull(category, "通知类别不能为空");
        this.severity = Objects.requireNonNull(severity, "通知严重度不能为空");
        this.title = requireText(title, 200, "标题");
        this.content = requireText(content, 1000, "内容");
        this.sourceType = Objects.requireNonNull(sourceType, "来源类型不能为空");
        this.sourceRef = requireText(sourceRef, 128, "来源编号");
        this.readAt = readAt;
        this.createdAt = Objects.requireNonNull(createdAt, "创建时间不能为空");
    }

    public static SystemNotification create(long tenantId, long recipientUserId, Category category,
                                            Severity severity, String title, String content,
                                            SourceType sourceType, String sourceRef, Instant createdAt) {
        return new SystemNotification(null, tenantId, recipientUserId, category, severity, title, content,
                sourceType, sourceRef, null, createdAt);
    }

    /** 持久层重建聚合。 */
    public static SystemNotification restore(long id, long tenantId, long recipientUserId, Category category,
                                             Severity severity, String title, String content,
                                             SourceType sourceType, String sourceRef, Instant readAt,
                                             Instant createdAt) {
        if (id < 1) {
            throw new IllegalArgumentException("通知 id 必须为正数");
        }
        return new SystemNotification(id, tenantId, recipientUserId, category, severity, title, content,
                sourceType, sourceRef, readAt, createdAt);
    }

    /** 只在首次阅读时记录时间，重复请求保持原值。 */
    public void markRead(Instant readAt) {
        Instant checkedReadAt = Objects.requireNonNull(readAt, "已读时间不能为空");
        if (this.readAt == null) {
            this.readAt = checkedReadAt;
        }
    }

    /** 仓储插入后回填主键，只允许一次。 */
    public void assignId(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("通知 id 必须为正数");
        }
        if (this.id != null) {
            throw new IllegalStateException("通知 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    private static long requireTenantId(long tenantId) {
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户 id 不能为负数");
        }
        return tenantId;
    }

    private static String requireText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "不能超过 " + maxLength + " 个字符");
        }
        return normalized;
    }

    public Long id() { return id; }
    public long tenantId() { return tenantId; }
    public long recipientUserId() { return recipientUserId; }
    public Category category() { return category; }
    public Severity severity() { return severity; }
    public String title() { return title; }
    public String content() { return content; }
    public SourceType sourceType() { return sourceType; }
    public String sourceRef() { return sourceRef; }
    public Instant readAt() { return readAt; }
    public Instant createdAt() { return createdAt; }

    @Override
    public Long auditTargetId() { return id; }

    @Override
    public String auditTargetCode() { return sourceRef; }

    @Override
    public String auditSummary() {
        return "类别=" + category + ", 严重度=" + severity + ", 来源=" + sourceType
                + ", 已读=" + (readAt != null);
    }
}
