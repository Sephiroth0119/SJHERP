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
    SALES_THREE_WAY("SALES_THREE_WAY", "销售三单数量勾稽"),

    /**
     * 规则8（M4-T04c）：核销 rollup 一致——每笔应收/应付 settled_amount = Σ 对应核销记录金额。
     * 子账 settled_amount 是核销记录（settlement_record）的维护型 rollup，二者必须分毫不差（原则 4 账实一致）。
     */
    SETTLEMENT_ROLLUP("SETTLEMENT_ROLLUP", "核销 rollup 一致"),

    /**
     * 规则9（M4-T04c）：无超额持久化——每笔应收/应付 settled_amount ≤ amount。
     * 领域层 settle() 已硬拒超额（OverSettlementException），本规则为越权直插库的兜底防线（理论恒成立）。
     */
    SETTLEMENT_OVER("SETTLEMENT_OVER", "核销无超额"),

    /**
     * 规则10（M4-T04c）：状态-余额一致——SETTLED⟺余额0、PARTIAL⟺0&lt;已核销&lt;amount、OPEN⟺已核销0。
     * 子账 status 与（amount−settled_amount）派生余额必须互洽，否则状态机被旁路写入。
     */
    SETTLEMENT_STATUS("SETTLEMENT_STATUS", "核销状态-余额一致");

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
