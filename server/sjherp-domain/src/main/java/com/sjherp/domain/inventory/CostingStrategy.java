package com.sjherp.domain.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 存货成本计算策略接口（M3-T01a，拆解 §1.7 Q-2 预留）。
 *
 * <p>v1.0 唯一实现为移动加权 {@link MovingWeightedAverageCalculator}，
 * 全局配置 {@code sjherp.inventory.costing-method=MOVING_AVERAGE}（app 层装配时选取）。
 * FIFO 留实现位不留死代码。实现必须是纯函数：不读写任何外部状态。
 */
public interface CostingStrategy {

    /** 单价统一精度：6 位小数（DECIMAL(18,6)） */
    int UNIT_COST_SCALE = 6;

    /** 金额统一精度：2 位小数（DECIMAL(18,2)） */
    int AMOUNT_SCALE = 2;

    /** 全局统一舍入模式：四舍五入 */
    RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /**
     * 金额计算：{@code total = (unitCost × quantity).setScale(2, HALF_UP)}。
     * 入库金额与负库存退化口径的出库金额都走这一步——舍入只发生在 total。
     */
    BigDecimal roundedTotal(BigDecimal unitCost, BigDecimal quantity);

    /**
     * 当前结存加权单价：{@code costAmount / quantity} 取 6 位 HALF_UP（派生值，不落库）。
     * 盘盈默认入库价与报表展示同此口径。要求 {@code balanceQuantity > 0}，否则拒绝。
     */
    BigDecimal weightedUnitCost(BigDecimal balanceQuantity, BigDecimal balanceAmount);

    /**
     * 出库定价（拆解 §1.6.2）：
     * <ol>
     *   <li>单价 = 出库时点加权单价（{@link #weightedUnitCost}，写入流水快照）；</li>
     *   <li>金额 = {@link #roundedTotal}；</li>
     *   <li><b>出空清零</b>：出库数量 == 结存数量时金额改取出库前全部结存金额
     *       （覆盖第 2 步结果），吸收尾差，保证不残留「数量 0 金额非 0」行。</li>
     * </ol>
     * 要求 {@code balanceQuantity > 0}（负库存退化口径由 {@link InventoryService} 处理）。
     *
     * @param quantity 出库数量（正数，基本单位），允许大于结存（负库存开关打开时照常加权）
     */
    OutboundCost priceOutbound(BigDecimal quantity, BigDecimal balanceQuantity, BigDecimal balanceAmount);

    /**
     * 出库定价结果（均为正数口径，符号由 {@link InventoryService} 落流水时统一加负）。
     *
     * @param unitCost      出库时点加权单价（6 位）
     * @param totalCost     出库成本金额（2 位）
     * @param clearedToZero 是否触发出空清零（金额直接取出库前结存金额）
     */
    record OutboundCost(BigDecimal unitCost, BigDecimal totalCost, boolean clearedToZero) {
    }
}
