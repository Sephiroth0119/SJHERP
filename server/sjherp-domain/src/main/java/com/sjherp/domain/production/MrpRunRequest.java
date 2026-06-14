package com.sjherp.domain.production;

/**
 * MRP 运行请求参数（M5-T02）。
 *
 * @param warehouseId        库存核算仓库 id
 * @param includeForecast    是否包含手工预测（DemandPlan ENABLED 行）
 * @param includeSalesOrder  是否包含销售订单未交货需求
 * @param remark             本次运行备注（可空）
 */
public record MrpRunRequest(
        long warehouseId,
        boolean includeForecast,
        boolean includeSalesOrder,
        String remark) {
}
