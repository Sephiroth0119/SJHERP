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
    SETTLEMENT_STATUS("SETTLEMENT_STATUS", "核销状态-余额一致"),

    /**
     * 规则11（M5-T06，D9，WARN 非阻塞）：已完工工单的工费已结转——
     * 有完工产出（completed_qty&gt;0）的 EXECUTING/COMPLETED 工单，应有对应的已过账成本结转行。
     * 缺失 = 完工工费尚未月末结转（提醒，不阻塞关账，避免误卡）。
     */
    WORK_ORDER_COST_UNSETTLED("WORK_ORDER_COST_UNSETTLED", "完工工单工费结转"),

    /**
     * 规则12（M5-T08，ERROR）：领料/退料成本勾稽——
     * 每张 COMPLETED 领料单行 {@code issued_cost} ≡ −Σ(PRODUCTION_ISSUE 库存流水 total_cost，
     * 按领料单号+行号匹配)；每张 COMPLETED 退料单行 {@code returned_cost} ≡ Σ(PRODUCTION_RETURN 流水 total_cost)。
     * 领料/退料过账经库存唯一入口，回填成本必须与流水分毫不差（缺流水或不符 = 账实不一致）。
     */
    MATERIAL_ISSUE_COST("MATERIAL_ISSUE_COST", "领料退料成本勾稽"),

    /**
     * 规则13（M5-T08，ERROR）：完工入库成本勾稽——
     * 每张 COMPLETED 报工单 {@code inbound_cost} ≡ Σ(PRODUCTION_IN 库存流水 total_cost，按报工单号匹配)。
     * 完工入库经库存唯一入口，回填入库成本必须与流水一致。
     */
    PRODUCTION_INBOUND_COST("PRODUCTION_INBOUND_COST", "完工入库成本勾稽"),

    /**
     * 规则14（M5-T08，ERROR/WARN）：工单料费守恒（R1 硬边界）——
     * 每工单 Σ完工入库料金额（PRODUCTION_IN inbound_cost）vs Σ领料净出库料金额
     * （Σ COMPLETED 领料 issued_cost − Σ COMPLETED 退料 returned_cost）：
     * diff=Σinbound−Σissued_net &gt; 0.01（1 分容差）→ ERROR（料虚增，R1 破，料凭空增值）；
     * 0 &lt; diff ≤ 0.01 入库 round2×qty 舍入残差不报；
     * Σinbound &lt; Σissued_net → WARN（差额 = 在产 WIP 料，正常未完工）；diff=0 = 守恒。
     * 这是 M5 生产链里程碑出口的核心 ERROR 级勾稽（此前料虚增可静默逃逸关账闸门）。
     */
    WORK_ORDER_MATERIAL_CONSERVATION("WORK_ORDER_MATERIAL_CONSERVATION", "工单料费守恒"),

    /**
     * 规则15（M5-T08，ERROR）：工单完工量勾稽——
     * {@code work_order.completed_qty} ≡ Σ(该工单已过账 COMPLETED 报工 completed_qty)。
     * recordCompletion 累加回写若被旁路（直插库/漏写），工单完工量与报工汇总即对不上。
     */
    WORK_ORDER_COMPLETED_QTY("WORK_ORDER_COMPLETED_QTY", "工单完工量勾稽"),

    /**
     * 规则16（M5-T08，ERROR）：成本结转工费追加勾稽——
     * 每张 COMPLETED 成本结转单行的完工工费增量（completed_cost − material_cost − already_transferred，
     * 截 0 下限）≡ Σ(COST_ADJUST 库存流水 total_cost，按结转单号+行号匹配)。
     * 工费经库存唯一入口 CostAdjust 追加到产成品，结转行口径必须与流水一致（双锚点探测网，R-T06-8）。
     */
    COST_SETTLEMENT_ADJUST("COST_SETTLEMENT_ADJUST", "成本结转工费勾稽");

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
