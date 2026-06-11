package com.sjherp.domain.common.event;

import com.sjherp.domain.common.DocumentStatus;

import java.util.Objects;

/**
 * 单据状态流转事件。
 *
 * <p>每一次合法的单据状态流转都会自动发布本事件（由
 * {@link com.sjherp.domain.common.BusinessDocument} 在流转后产生），
 * 供审计日志、凭证生成、检查 Agent 等下游订阅。聚合标识即单据号。
 */
public final class DocumentStatusChangedEvent extends DomainEvent {

    /** 流转前状态 */
    private final DocumentStatus fromStatus;

    /** 流转后状态 */
    private final DocumentStatus toStatus;

    /** 操作人（用户或 Agent 标识，审计要求） */
    private final String operator;

    public DocumentStatusChangedEvent(String docNo,
                                      DocumentStatus fromStatus,
                                      DocumentStatus toStatus,
                                      String operator) {
        super(docNo);
        this.fromStatus = Objects.requireNonNull(fromStatus, "fromStatus 不能为空");
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus 不能为空");
        this.operator = Objects.requireNonNull(operator, "operator 不能为空");
    }

    /** 单据编号（即聚合标识） */
    public String getDocNo() {
        return getAggregateId();
    }

    public DocumentStatus getFromStatus() {
        return fromStatus;
    }

    public DocumentStatus getToStatus() {
        return toStatus;
    }

    public String getOperator() {
        return operator;
    }
}
