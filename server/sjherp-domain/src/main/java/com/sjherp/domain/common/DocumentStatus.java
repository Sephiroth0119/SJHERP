package com.sjherp.domain.common;

import java.util.Map;
import java.util.Set;

/**
 * 单据状态机（CLAUDE.md：单据皆为状态机，状态流转规则定义在领域层）。
 *
 * <p>草稿 → 审核 → 执行 → 完成/冲销。COMPLETED 之后只允许 REVERSED（冲销），
 * 体现"财务记录只可冲销、不可物理修改/删除"的不可妥协原则。
 * 各单据类型如需收紧规则，在各自领域服务中追加校验，但不得放宽本处定义。
 */
public enum DocumentStatus {
    /** 草稿：可编辑、可删除 */
    DRAFT,
    /** 已审核：业务内容锁定，等待执行 */
    APPROVED,
    /** 执行中：已产生部分库存/资金影响 */
    EXECUTING,
    /** 已完成：全部影响已落账，不可修改，只可冲销 */
    COMPLETED,
    /** 已冲销：终态，由红字/反向记录抵消原单影响 */
    REVERSED;

    /** 合法流转表：当前状态 -> 允许的下一状态集合 */
    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(APPROVED),
            APPROVED, Set.of(EXECUTING, REVERSED),
            EXECUTING, Set.of(COMPLETED, REVERSED),
            COMPLETED, Set.of(REVERSED),
            REVERSED, Set.of()
    );

    /** 是否允许流转到目标状态 */
    public boolean canTransitionTo(DocumentStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /**
     * 校验并返回目标状态，非法流转直接抛异常——宁可拒绝，不可破坏模型。
     */
    public DocumentStatus transitionTo(DocumentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法的单据状态流转: " + this + " -> " + target);
        }
        return target;
    }
}
