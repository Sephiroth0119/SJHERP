package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Map;

/**
 * MRP 需求来源端口——销售订单侧实现（M5-T02，domain 端口，infra 适配）。
 *
 * <p>返回所有 APPROVED/EXECUTING 销售订单行的未交货剩余需求，按商品 id 聚合，
 * 数量已换算为基本单位（SalesOrderLine.remainingQty()，单据本身以基本单位存储）。
 */
public interface MrpDemandSource {

    /**
     * 当前开放销售订单需求（商品 id → 基本单位未交货数量）。
     * 数量 ≤ 0 的记录不返回（remainingQty == 0 表示已全交，应排除）。
     */
    Map<Long, BigDecimal> openSalesOrderDemand();
}
