package com.sjherp.app.consistency;

/**
 * 一致性校验规则类型（M3-T13 检查 Agent，七条勾稽规则一一对应）。
 *
 * <p>每条规则带稳定 {@code code}（机读、与 docs 对齐）与中文 {@code displayName}（人读）。
 * 业务含义见 docs 业务文档「数据一致性校验」§2 的七条勾稽表。
 */
public enum ConsistencyCheckType {

    /** 规则1：每个(仓库,商品) Σ库存流水.数量 = 库存余额.数量 */
    LEDGER_QUANTITY("LEDGER_QUANTITY", "库存数量恒等式"),

    /** 规则2：每个(仓库,商品) Σ库存流水.金额 = 库存余额.结存金额 */
    LEDGER_COST("LEDGER_COST", "库存金额恒等式"),

    /** 规则3：库存余额非负（数量、金额均 ≥ 0；禁负库存时为硬约束） */
    NEGATIVE_BALANCE("NEGATIVE_BALANCE", "库存余额非负"),

    /** 规则4：每张已过账采购发票生成的应付金额 = 发票金额 */
    PAYABLE_AMOUNT("PAYABLE_AMOUNT", "应付金额勾稽"),

    /** 规则5：每张已过账销售发票生成的应收金额 = 发票金额 */
    RECEIVABLE_AMOUNT("RECEIVABLE_AMOUNT", "应收金额勾稽"),

    /** 规则6：销售出库行的 COGS = 该出库对应 SALES_OUT 流水金额合计（绝对值） */
    COGS_MISMATCH("COGS_MISMATCH", "销货成本勾稽"),

    /** 规则7：采购三单数量勾稽（已开票量 ≤ 已收量 ≤ 订单量） */
    PURCHASE_THREE_WAY("PURCHASE_THREE_WAY", "采购三单数量勾稽"),

    /** 规则7：销售三单数量勾稽（已开票量 ≤ 已发量 ≤ 订单量） */
    SALES_THREE_WAY("SALES_THREE_WAY", "销售三单数量勾稽");

    private final String code;
    private final String displayName;

    ConsistencyCheckType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 机读稳定编码（与 docs 对齐） */
    public String code() {
        return code;
    }

    /** 中文展示名（人读） */
    public String displayName() {
        return displayName;
    }
}
