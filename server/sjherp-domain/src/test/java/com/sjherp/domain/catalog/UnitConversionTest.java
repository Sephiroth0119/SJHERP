package com.sjherp.domain.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 多单位换算的 BigDecimal 边界测试（不可妥协原则 5）。
 */
class UnitConversionTest {

    @Test
    void 整数换算率_箱转瓶() {
        // 1 箱 = 12 瓶
        UnitConversion conversion = new UnitConversion(2L, new BigDecimal("12"));
        assertEquals(0, new BigDecimal("36").compareTo(conversion.toBaseQuantity(new BigDecimal("3"))));
    }

    @Test
    void 小数换算率_乘法不丢精度() {
        // 1 件 = 2.5 千克；0.4 件 = 1.00 千克（乘法精确，不舍入）
        UnitConversion conversion = new UnitConversion(3L, new BigDecimal("2.5"));
        assertEquals(0, BigDecimal.ONE.compareTo(conversion.toBaseQuantity(new BigDecimal("0.4"))));
    }

    @Test
    void 除不尽时按目标精度HALF_UP舍入() {
        // 1 箱 = 12 瓶：1 瓶 = 0.0833 箱（4 位）；2 瓶 = 0.1667 箱（进位边界）
        UnitConversion conversion = new UnitConversion(2L, new BigDecimal("12"));
        assertEquals(new BigDecimal("0.0833"), conversion.fromBaseQuantity(BigDecimal.ONE, 4));
        assertEquals(new BigDecimal("0.1667"), conversion.fromBaseQuantity(new BigDecimal("2"), 4));
        // 精度 0：6 瓶 = 0.5 箱 → HALF_UP 进到 1 箱
        assertEquals(new BigDecimal("1"), conversion.fromBaseQuantity(new BigDecimal("6"), 0));
    }

    @Test
    void 换算率最小边界_六位小数() {
        UnitConversion conversion = new UnitConversion(2L, new BigDecimal("0.000001"));
        assertEquals(0, new BigDecimal("0.000001").compareTo(conversion.toBaseQuantity(BigDecimal.ONE)));
    }

    @Test
    void 换算率超过六位小数被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new UnitConversion(2L, new BigDecimal("0.0000001")));
    }

    @Test
    void 尾零不算超精度() {
        // 12.000000 实际 0 位小数，stripTrailingZeros 后不超限
        UnitConversion conversion = new UnitConversion(2L, new BigDecimal("12.000000"));
        assertEquals(0, new BigDecimal("12").compareTo(conversion.rate()));
    }

    @Test
    void 换算率必须为正() {
        assertThrows(IllegalArgumentException.class, () -> new UnitConversion(2L, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new UnitConversion(2L, new BigDecimal("-12")));
        assertThrows(NullPointerException.class, () -> new UnitConversion(2L, null));
    }

    @Test
    void 大数量换算不溢出不失真() {
        // BigDecimal 无溢出问题，验证大数 × 大换算率仍精确
        // 999999.999999 × 1000000 = 999999999999（精确，小数部分全为 0）
        UnitConversion conversion = new UnitConversion(2L, new BigDecimal("999999.999999"));
        BigDecimal result = conversion.toBaseQuantity(new BigDecimal("1000000"));
        assertEquals(0, new BigDecimal("999999999999").compareTo(result));
    }

    @Test
    void 负数精度参数被拒绝() {
        UnitConversion conversion = new UnitConversion(2L, new BigDecimal("12"));
        assertThrows(IllegalArgumentException.class,
                () -> conversion.fromBaseQuantity(BigDecimal.ONE, -1));
    }

    @Test
    void 往返换算误差受控() {
        // 7 瓶 → 箱（4 位精度）→ 瓶，误差应小于 0.01 瓶
        UnitConversion conversion = new UnitConversion(2L, new BigDecimal("12"));
        BigDecimal boxes = conversion.fromBaseQuantity(new BigDecimal("7"), 4); // 0.5833
        BigDecimal back = conversion.toBaseQuantity(boxes); // 6.9996
        assertTrue(new BigDecimal("7").subtract(back).abs().compareTo(new BigDecimal("0.01")) < 0);
    }
}
