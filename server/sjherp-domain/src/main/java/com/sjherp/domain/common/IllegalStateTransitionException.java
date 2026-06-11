package com.sjherp.domain.common;

import java.util.Objects;

/**
 * 非法单据状态流转的领域异常。
 *
 * <p>携带单据号、当前状态、目标状态三要素，便于审计日志与 Agent
 * 向用户解释拒绝原因。宁可拒绝一个需求，不可破坏一次模型（CLAUDE.md 原则 1）。
 */
public class IllegalStateTransitionException extends RuntimeException {

    /** 单据编号 */
    private final String docNo;

    /** 流转前的当前状态 */
    private final DocumentStatus currentStatus;

    /** 被拒绝的目标状态 */
    private final DocumentStatus targetStatus;

    public IllegalStateTransitionException(String docNo,
                                           DocumentStatus currentStatus,
                                           DocumentStatus targetStatus) {
        super("非法的单据状态流转: 单据[" + docNo + "] " + currentStatus + " -> " + targetStatus);
        this.docNo = Objects.requireNonNull(docNo, "docNo 不能为空");
        this.currentStatus = Objects.requireNonNull(currentStatus, "currentStatus 不能为空");
        this.targetStatus = Objects.requireNonNull(targetStatus, "targetStatus 不能为空");
    }

    public String getDocNo() {
        return docNo;
    }

    public DocumentStatus getCurrentStatus() {
        return currentStatus;
    }

    public DocumentStatus getTargetStatus() {
        return targetStatus;
    }
}
