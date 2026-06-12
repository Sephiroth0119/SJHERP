package com.sjherp.domain.inventory;

import java.math.BigDecimal;

/**
 * 成本调整指令（COST_ADJUST，拆解 §1.6.4）：数量不变、只调金额
 * （典型场景：到票价差、运费入成本）。
 *
 * <p>约束（由 {@link InventoryService} 强制）：当前结存数量 > 0（无数量无成本可调）、
 * 调整后结存金额 ≥ 0、调整额非 0 且最多 2 位小数。调整即时改变后续出库加权单价。
 *
 * @param adjustAmount 调整额（可正可负，最多 2 位小数）
 */
public record CostAdjustCommand(long warehouseId, long productId, BigDecimal adjustAmount,
                                String srcDocType, String srcDocNo, Integer srcLineNo,
                                String idempotencyKey) implements StockMovementCommand {

    /** 成本调整类型固定，调用方无须（也不能）另行指定 */
    @Override
    public InventoryTxnType txnType() {
        return InventoryTxnType.COST_ADJUST;
    }
}
