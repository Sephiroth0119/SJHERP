package com.sjherp.domain.payable;

/**
 * 应付账款状态（M3-T07）。
 *
 * <p>本期（M3）只产生 OPEN（未核销）应付——核销（付款冲应付，含部分核销）在 M4-T03 落地，
 * 届时引入 PARTIAL（部分核销）/ SETTLED（已核销）状态与已核销金额字段。
 */
public enum PayableStatus {

    /** 未核销：发票过账即生成，等待付款核销（M4-T03） */
    OPEN("未核销"),

    /** 部分核销（M4-T03 预留，本期不产生） */
    PARTIAL("部分核销"),

    /** 已核销（M4-T03 预留，本期不产生） */
    SETTLED("已核销");

    private final String label;

    PayableStatus(String label) {
        this.label = label;
    }

    /** 中文展示名（审计摘要 / 用户可见文案统一出口） */
    public String label() {
        return label;
    }
}
