package com.sjherp.domain.receivable;

/**
 * 应收账款状态（M3-T10）。
 *
 * <p>v1.0 仅落 OPEN（未核销）。核销（收款冲减）在 M4-T03 落地，届时引入 PARTIAL（部分核销）/
 * SETTLED（已核销）状态与已核销金额字段。本枚举先把状态位预留好。
 */
public enum ReceivableStatus {

    /** 未核销（开票即产生，待收款核销） */
    OPEN("未核销"),

    /** 部分核销（M4-T03 收款核销引入） */
    PARTIAL("部分核销"),

    /** 已核销（M4-T03 收款核销引入） */
    SETTLED("已核销"),

    /** 已冲销（M4-T07b 业务单据红冲：应收随销售发票红冲整笔冲回，须无核销才可冲） */
    REVERSED("已冲销");

    private final String label;

    ReceivableStatus(String label) {
        this.label = label;
    }

    /** 中文展示名（审计摘要、Agent 文案用） */
    public String label() {
        return label;
    }
}
