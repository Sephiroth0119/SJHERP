package com.sjherp.domain.gap;

import java.util.Set;

/**
 * 流程缺口状态（简单状态流转，**不是**单据状态机 {@code DocumentStatus}——
 * 缺口记录不产生业务影响，不需要冲销语义，允许直接驳回）。
 *
 * <p>合法流转表（单一真源在本枚举）：
 * <pre>
 * NEW → TRIAGED / REJECTED
 * TRIAGED → IN_DEVELOPMENT / REJECTED
 * IN_DEVELOPMENT → RESOLVED / REJECTED
 * RESOLVED / REJECTED：终态
 * </pre>
 */
public enum GapStatus {

    /** 新记录（Agent 落库后的初始状态） */
    NEW,

    /** 已评估（开发侧确认了缺口有效并排期） */
    TRIAGED,

    /** 开发中 */
    IN_DEVELOPMENT,

    /** 已解决（功能上线；M6-T10 据此回写通知原会话用户 + 写入大记忆） */
    RESOLVED,

    /** 已驳回（重复、误报、超出产品范围等） */
    REJECTED;

    /** 是否允许从当前状态流转到目标状态 */
    public boolean canTransitionTo(GapStatus target) {
        return allowedTargets().contains(target);
    }

    /** 当前状态允许流转到的目标集合（终态为空集） */
    public Set<GapStatus> allowedTargets() {
        return switch (this) {
            case NEW -> Set.of(TRIAGED, REJECTED);
            case TRIAGED -> Set.of(IN_DEVELOPMENT, REJECTED);
            case IN_DEVELOPMENT -> Set.of(RESOLVED, REJECTED);
            case RESOLVED, REJECTED -> Set.of();
        };
    }
}
