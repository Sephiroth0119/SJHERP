package com.sjherp.domain.inventory;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 移动加权平均成本计算器（M3-T01a，拆解 §1.6 精确口径）——纯函数，无状态。
 *
 * <p>口径要点（对账能否平的关键）：
 * <ul>
 *   <li>单价 6 位 HALF_UP、金额 2 位 HALF_UP，舍入只发生在 total 一步；</li>
 *   <li>出空清零吸收尾差：出库后数量为 0 时金额全额带走；</li>
 *   <li>余额扣减必须用<b>已舍入</b>的 total（由调用方 {@link InventoryService} 保证），
 *       使「余额金额 = Σ流水 total_cost」恒等式严格成立。</li>
 * </ul>
 */
public final class MovingWeightedAverageCalculator implements CostingStrategy {

    @Override
    public BigDecimal roundedTotal(BigDecimal unitCost, BigDecimal quantity) {
        Objects.requireNonNull(unitCost, "unitCost 不能为空");
        Objects.requireNonNull(quantity, "quantity 不能为空");
        return unitCost.multiply(quantity).setScale(AMOUNT_SCALE, ROUNDING);
    }

    @Override
    public BigDecimal weightedUnitCost(BigDecimal balanceQuantity, BigDecimal balanceAmount) {
        Objects.requireNonNull(balanceQuantity, "balanceQuantity 不能为空");
        Objects.requireNonNull(balanceAmount, "balanceAmount 不能为空");
        if (balanceQuantity.signum() <= 0) {
            throw new IllegalArgumentException(
                    "结存数量必须大于 0 才能计算加权单价: " + balanceQuantity.toPlainString());
        }
        return balanceAmount.divide(balanceQuantity, UNIT_COST_SCALE, ROUNDING);
    }

    @Override
    public OutboundCost priceOutbound(BigDecimal quantity, BigDecimal balanceQuantity,
                                      BigDecimal balanceAmount) {
        Objects.requireNonNull(quantity, "quantity 不能为空");
        BigDecimal unitCost = weightedUnitCost(balanceQuantity, balanceAmount);
        // 出空清零（§1.6.2c）：兜底保险——公式结果通常与余额一致，但极端比例下
        // （如余额 1000000 个 / 0.01 元）会差出尾差，规则路径保证余额行回到 (0, 0.00)
        boolean clearedToZero = balanceQuantity.compareTo(quantity) == 0;
        BigDecimal totalCost = clearedToZero
                ? balanceAmount.setScale(AMOUNT_SCALE, ROUNDING)
                : roundedTotal(unitCost, quantity);
        return new OutboundCost(unitCost, totalCost, clearedToZero);
    }
}
