package com.sjherp.domain.settlement;

/**
 * 核销类型（M4-T03）：区分本条核销记录冲减的是应收还是应付。
 *
 * <p>即 {@code settlement_record.settlement_type} 列的字典值。
 */
public enum SettlementType {

    /** 应收核销（收款冲减应收账款，target = accounts_receivable.id） */
    RECEIVABLE("应收核销"),

    /** 应付核销（付款冲减应付账款，target = accounts_payable.id） */
    PAYABLE("应付核销");

    private final String label;

    SettlementType(String label) {
        this.label = label;
    }

    /** 中文展示名（审计摘要 / 用户可见文案统一出口） */
    public String label() {
        return label;
    }
}
