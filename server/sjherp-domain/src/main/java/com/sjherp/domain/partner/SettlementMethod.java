package com.sjherp.domain.partner;

/**
 * 往来单位结算方式：月结 / 现结 / 预付。
 *
 * <p>客户与供应商共用。结算方式影响 M4 应收应付的核销与账期策略
 * （月结生成账期应收应付，现结一单一结，预付先款后货）。
 */
public enum SettlementMethod {

    /** 月结：按月对账后结算 */
    MONTHLY,

    /** 现结：货到付款，一单一结 */
    CASH,

    /** 预付：先款后货 */
    PREPAID
}
