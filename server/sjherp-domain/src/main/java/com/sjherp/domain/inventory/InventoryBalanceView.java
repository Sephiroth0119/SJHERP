package com.sjherp.domain.inventory;

import java.math.BigDecimal;

/**
 * 库存余额只读视图（M3-T01a，拆解 §1.1）。
 *
 * <p>真源只有 quantity 与 costAmount 两列，加权单价是派生值<b>不冗余存储</b>
 * （存两份必然漂移，见拆解 §2 第 4 步），读取/报表时按 6 位 HALF_UP 现算。
 */
public record InventoryBalanceView(long warehouseId, long productId,
                                   BigDecimal quantity, BigDecimal costAmount) {

    /** 无余额行时的零视图（数量 0.000000 / 金额 0.00） */
    public static InventoryBalanceView empty(long warehouseId, long productId) {
        return new InventoryBalanceView(warehouseId, productId,
                BigDecimal.ZERO.setScale(CostingStrategy.UNIT_COST_SCALE),
                BigDecimal.ZERO.setScale(CostingStrategy.AMOUNT_SCALE));
    }

    /**
     * 派生加权单价（{@code costAmount / quantity}，6 位 HALF_UP）。
     * 数量 ≤ 0 时单价无意义，返回 null（展示层显示「—」；负库存期间成本为估计值）。
     */
    public BigDecimal derivedUnitCost() {
        if (quantity.signum() <= 0) {
            return null;
        }
        return costAmount.divide(quantity, CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
    }
}
