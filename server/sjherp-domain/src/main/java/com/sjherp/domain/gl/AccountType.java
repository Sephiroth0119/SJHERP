package com.sjherp.domain.gl;

/**
 * 会计科目类别（M4-T01，小企业会计准则模板五大类）。
 *
 * <p>类别决定科目的报表归属与默认余额方向口径；预置科目类别不可修改
 * （{@link Account} is_preset 守门），保证账表勾稽口径稳定。
 */
public enum AccountType {

    /** 资产 */
    ASSET("资产"),

    /** 负债 */
    LIABILITY("负债"),

    /** 所有者权益 */
    EQUITY("所有者权益"),

    /** 成本 */
    COST("成本"),

    /** 损益（收入/费用类） */
    PROFIT_LOSS("损益");

    private final String label;

    AccountType(String label) {
        this.label = label;
    }

    /** 中文标签（审计摘要 / 用户可见文案统一出口） */
    public String label() {
        return label;
    }
}
