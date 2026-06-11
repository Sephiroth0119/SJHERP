package com.sjherp.domain.common;

import java.time.Instant;
import java.util.Objects;

/**
 * 业务单据基类（示意性骨架，后续采购/销售/工单等单据继承）。
 *
 * <p>统一承载：单据号、状态机、审计字段。状态流转只能通过
 * {@link #changeStatus} 进行，保证规则不可绕过且每次流转可审计。
 */
public abstract class BusinessDocument {

    /** 单据编号（业务唯一） */
    private final String docNo;

    /** 单据状态 */
    private DocumentStatus status;

    /** 创建人（用户或 Agent 标识，审计要求） */
    private final String createdBy;

    private final Instant createdAt;

    /** 最后操作人（审计要求） */
    private String updatedBy;

    private Instant updatedAt;

    protected BusinessDocument(String docNo, String createdBy) {
        this.docNo = Objects.requireNonNull(docNo, "docNo 不能为空");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy 不能为空");
        this.status = DocumentStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedBy = createdBy;
        this.updatedAt = this.createdAt;
    }

    /**
     * 状态流转的唯一入口：非法流转抛异常，合法流转记录操作人。
     *
     * @param target   目标状态
     * @param operator 操作人（用户或 Agent 标识，写审计日志用）
     */
    public void changeStatus(DocumentStatus target, String operator) {
        this.status = this.status.transitionTo(target);
        this.updatedBy = Objects.requireNonNull(operator, "operator 不能为空");
        this.updatedAt = Instant.now();
    }

    public String getDocNo() {
        return docNo;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
