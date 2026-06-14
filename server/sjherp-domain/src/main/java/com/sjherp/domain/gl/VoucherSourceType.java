package com.sjherp.domain.gl;

/**
 * 凭证来源单据类型（M4-T02，即 {@code voucher.source_doc_type} 列字典）。
 *
 * <p>本批仅覆盖四个采购/销售过账事件——业务单据过账后自动生成对应记账凭证时回填，
 * 与来源单据号 {@code source_doc_no} 共同构成自动凭证幂等键（拆解 §3）。
 * T01 手工凭证两列均为 null（不属于任何来源类型）。
 *
 * <p>延后（拆解 §0）：期初建账 OPENING、盘点 COUNT_GAIN/LOSS、成本调整 COST_ADJUST 留后续小批；
 * TRANSFER 调拨永不出凭证（仓间移库 1405 总额不变，无 GL 影响）。
 */
public enum VoucherSourceType {

    /** 采购入库过账：借 1405 库存商品，贷 220201 暂估应付款 */
    PURCHASE_RECEIPT,

    /** 采购发票过账：借 220201 暂估应付款，贷 220202 应付账款 */
    PURCHASE_INVOICE,

    /** 销售出库过账：借 6401 主营业务成本，贷 1405 库存商品 */
    SALES_DELIVERY,

    /** 销售发票过账：借 1122 应收账款，贷 6001 主营业务收入 */
    SALES_INVOICE,

    /** 收款单过账：借 现金/银行（资金账户 glAccountCode），贷 1122 应收账款（M4-T04） */
    COLLECTION_RECEIPT,

    /** 付款单过账：借 220202 应付账款，贷 现金/银行（资金账户 glAccountCode）（M4-T04） */
    PAYMENT_DISBURSEMENT,

    /**
     * 期末结转损益（M4-T05）：账结法月结，把损益类（{@link AccountType#PROFIT_LOSS}）科目本期
     * 净额结转入 4103 本年利润。来源单据号回填为账期键（yyyyMM，6 位），配合
     * {@code uk_voucher_source} 物理唯一兜底——每账期至多一张结转凭证（拆解 §5 决策 2）。
     */
    PERIOD_CLOSING,

    /**
     * 凭证冲销（M4-T07a 红字凭证）：对已过账凭证生成借贷对调（反向分录）的红字凭证。
     * 来源单据号 {@code source_doc_no} 回填为被冲销的原凭证号，配合 {@code uk_voucher_source}
     * 物理唯一兜底——每张原凭证至多一张红冲凭证（拆解 §1.3，应用层幂等 + 物理唯一双保险）。
     */
    VOUCHER_REVERSAL;

    /** 中文标签（凭证摘要 / 审计 / 用户可见文案统一出口） */
    public String label() {
        return switch (this) {
            case PURCHASE_RECEIPT -> "采购入库";
            case PURCHASE_INVOICE -> "采购发票";
            case SALES_DELIVERY -> "销售出库";
            case SALES_INVOICE -> "销售发票";
            case COLLECTION_RECEIPT -> "收款单";
            case PAYMENT_DISBURSEMENT -> "付款单";
            case PERIOD_CLOSING -> "期末结转";
            case VOUCHER_REVERSAL -> "凭证冲销";
        };
    }
}
