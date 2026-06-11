package com.sjherp.domain.common;

import java.util.Map;
import java.util.Set;

/**
 * 单据状态机（CLAUDE.md：单据皆为状态机，状态流转规则定义在领域层）。
 *
 * <p>合法流转表：
 * <pre>
 * DRAFT     → APPROVED | CANCELLED
 * APPROVED  → EXECUTING | REVERSED
 * EXECUTING → COMPLETED | REVERSED
 * COMPLETED → REVERSED
 * CANCELLED / REVERSED → 终态，不再流转
 * </pre>
 *
 * <p>COMPLETED 之后只允许 REVERSED（冲销），体现"财务记录只可冲销、
 * 不可物理修改/删除"的不可妥协原则（CLAUDE.md 原则 2）。DRAFT 尚未产生
 * 任何业务影响，允许直接作废（CANCELLED）。
 * 各单据类型如需收紧规则，在 {@link BusinessDocument#beforeTransition}
 * 钩子中追加校验，但不得放宽本处定义。
 */
public enum DocumentStatus {
    /** 草稿：可编辑，可作废（CANCELLED） */
    DRAFT,
    /** 已审核：业务内容锁定，等待执行 */
    APPROVED,
    /** 执行中：已产生部分库存/资金影响 */
    EXECUTING,
    /** 已完成：全部影响已落账，不可修改，只可冲销 */
    COMPLETED,
    /** 已冲销：终态，由红字/反向记录抵消原单影响，原单内容保持不变 */
    REVERSED,
    /** 已作废：终态，仅草稿可作废（未产生任何业务影响） */
    CANCELLED;

    /** 合法流转表：当前状态 -> 允许的下一状态集合 */
    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(APPROVED, CANCELLED),
            APPROVED, Set.of(EXECUTING, REVERSED),
            EXECUTING, Set.of(COMPLETED, REVERSED),
            COMPLETED, Set.of(REVERSED),
            REVERSED, Set.of(),
            CANCELLED, Set.of()
    );

    /** 是否允许流转到目标状态 */
    public boolean canTransitionTo(DocumentStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }

    /** 当前状态允许流转到的全部目标状态（不可变集合） */
    public Set<DocumentStatus> allowedTargets() {
        return TRANSITIONS.get(this);
    }

    /** 是否为终态（不再允许任何流转） */
    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
