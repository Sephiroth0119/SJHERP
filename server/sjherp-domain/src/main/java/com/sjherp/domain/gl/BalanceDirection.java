package com.sjherp.domain.gl;

/**
 * 科目余额方向（M4-T01）：借 / 贷。
 *
 * <p>资产、成本、损益（费用类）科目余额方向为借（DEBIT）；负债、所有者权益、
 * 损益（收入类）科目余额方向为贷（CREDIT）。方向用于报表取数与试算平衡口径。
 */
public enum BalanceDirection {

    /** 借方 */
    DEBIT("借"),

    /** 贷方 */
    CREDIT("贷");

    private final String label;

    BalanceDirection(String label) {
        this.label = label;
    }

    /** 中文标签（审计摘要 / 用户可见文案统一出口） */
    public String label() {
        return label;
    }
}
