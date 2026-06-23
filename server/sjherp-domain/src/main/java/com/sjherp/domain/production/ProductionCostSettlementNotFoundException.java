package com.sjherp.domain.production;

/**
 * 月末成本结转单不存在（M5-T06）。REST 层映射为 404。
 */
public class ProductionCostSettlementNotFoundException extends RuntimeException {

    public ProductionCostSettlementNotFoundException(String docNo) {
        super("月末成本结转单不存在: " + docNo);
    }
}
