package com.sjherp.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.inventory.CostingStrategy.OutboundCost;

/**
 * 移动加权计算器纯函数测试（拆解 §1.6 口径 + §2 关键验算点）。
 */
class MovingWeightedAverageCalculatorTest {

    private final MovingWeightedAverageCalculator calculator = new MovingWeightedAverageCalculator();

    private static BigDecimal num(String value) {
        return new BigDecimal(value);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, num(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual.toPlainString());
    }

    @Test
    void 金额计算_2位HALF_UP_舍位与进位边界() {
        // §2 步 4：10.894444 × 70 = 762.61108 → 762.61（舍）
        assertNum("762.61", calculator.roundedTotal(num("10.894444"), num("70")));
        // §2 步 6：10.602600 × 5 = 53.013 → 53.01（第三位 3 舍）
        assertNum("53.01", calculator.roundedTotal(num("10.602600"), num("5")));
        // §2 步 8：10.689655 × 100 = 1068.9655 → 1068.97（HALF_UP 进位验证点）
        assertNum("1068.97", calculator.roundedTotal(num("10.689655"), num("100")));
        assertEquals(2, calculator.roundedTotal(num("10.894444"), num("70")).scale());
    }

    @Test
    void 加权单价_6位HALF_UP() {
        // §2 步 4：1961.00 / 180 = 10.894444…
        assertNum("10.894444", calculator.weightedUnitCost(num("180"), num("1961.00")));
        // §2 步 6：1590.39 / 150 = 10.602600（整除验证点）
        assertNum("10.602600", calculator.weightedUnitCost(num("150"), num("1590.39")));
        // §2 步 8：1550.00 / 145 = 10.6896551… → 10.689655
        assertNum("10.689655", calculator.weightedUnitCost(num("145"), num("1550.00")));
        assertEquals(6, calculator.weightedUnitCost(num("180"), num("1961.00")).scale());
    }

    @Test
    void 加权单价_结存数量为零或负被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> calculator.weightedUnitCost(num("0"), num("0.00")));
        assertThrows(IllegalArgumentException.class,
                () -> calculator.weightedUnitCost(num("-3"), num("-30.00")));
    }

    @Test
    void 出库定价_常规路径_不触发清零() {
        OutboundCost cost = calculator.priceOutbound(num("70"), num("180"), num("1961.00"));
        assertNum("10.894444", cost.unitCost());
        assertNum("762.61", cost.totalCost());
        assertFalse(cost.clearedToZero());
    }

    @Test
    void 出空清零分支_金额直接取出库前结存金额() {
        // §2 验算点 9 说明的桩验证：出库数量 == 结存数量 → 走规则路径（total 取余额全额）
        OutboundCost cost = calculator.priceOutbound(num("3"), num("3"), num("10.00"));
        assertTrue(cost.clearedToZero());
        assertNum("3.333333", cost.unitCost());
        assertNum("10.00", cost.totalCost());
    }

    @Test
    void 出空清零分支_公式结果不等于余额的构造用例() {
        // 构造性证明清零规则不是空转：结存 1000000 个 / 0.01 元
        // 公式口径：单价 0.01/1000000 = 0.00000001 → 6 位 HALF_UP = 0.000000，
        // total = 0.000000 × 1000000 = 0.00 ≠ 0.01；清零规则全额带走 0.01
        OutboundCost cost = calculator.priceOutbound(num("1000000"), num("1000000"), num("0.01"));
        assertTrue(cost.clearedToZero());
        assertNum("0.000000", cost.unitCost());
        assertNum("0.01", cost.totalCost());
        // 对照：同口径按公式（非清零路径）确实算出 0.00
        assertNum("0.00", calculator.roundedTotal(cost.unitCost(), num("1000000")));
    }

    @Test
    void 出库定价_超出结存数量不触发清零_照常加权() {
        // 负库存放行场景：出库前 quantity > 0 照常加权（§1.5）
        OutboundCost cost = calculator.priceOutbound(num("15"), num("10"), num("50.00"));
        assertNum("5.000000", cost.unitCost());
        assertNum("75.00", cost.totalCost());
        assertFalse(cost.clearedToZero());
    }
}
