package com.sjherp.domain.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * BomLine 值对象单元测试（M5-T01）：grossQuantity 加成法、构造校验、入参校验。
 *
 * <p>纯 JUnit5，无 Spring Context。
 */
class BomLineTest {

    /** 任意合法子件 id / 单位 id */
    private static final long CHILD_ID = 10L;
    private static final long UNIT_ID  = 1L;

    // ================================================================ grossQuantity 加成法

    @Test
    void grossQuantity_损耗率为0_等于净需求() {
        BomLine line = new BomLine(CHILD_ID, new BigDecimal("5"), BigDecimal.ZERO, UNIT_ID);
        BigDecimal gross = line.grossQuantity(new BigDecimal("10"));
        // 10 × (1 + 0) = 10
        assertEquals(0, new BigDecimal("10").compareTo(gross),
                "scrapRate=0 时毛需求应等于净需求");
    }

    @Test
    void grossQuantity_损耗率5percent_结果为净需求乘以1_05() {
        BomLine line = new BomLine(CHILD_ID, BigDecimal.ONE, new BigDecimal("0.05"), UNIT_ID);
        BigDecimal gross = line.grossQuantity(new BigDecimal("100"));
        // 100 × 1.05 = 105
        assertEquals(0, new BigDecimal("105.00").compareTo(gross),
                "scrapRate=0.05 时 100 × 1.05 = 105");
    }

    @Test
    void grossQuantity_损耗率10percent_10乘以1_1等于11() {
        BomLine line = new BomLine(CHILD_ID, BigDecimal.ONE, new BigDecimal("0.1"), UNIT_ID);
        BigDecimal gross = line.grossQuantity(new BigDecimal("10"));
        // 10 × 1.1 = 11
        assertEquals(0, new BigDecimal("11.0").compareTo(gross),
                "scrapRate=0.1 时 10 × 1.1 = 11");
    }

    @Test
    void grossQuantity_损耗率接近1_毛需求显著大于净需求() {
        BomLine line = new BomLine(CHILD_ID, BigDecimal.ONE, new BigDecimal("0.999999"), UNIT_ID);
        BigDecimal net = new BigDecimal("10");
        BigDecimal gross = line.grossQuantity(net);
        // 毛需求 ≈ 10 × 1.999999 ≈ 19.99999，必须大于净需求
        assertTrue(gross.compareTo(net) > 0,
                "接近 scrapRate=1 时毛需求应显著大于净需求");
    }

    // ================================================================ 构造校验

    @Test
    void 构造_quantity为null_抛NullPointerException() {
        assertThrows(NullPointerException.class,
                () -> new BomLine(CHILD_ID, null, BigDecimal.ZERO, UNIT_ID));
    }

    @Test
    void 构造_quantity为0_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new BomLine(CHILD_ID, BigDecimal.ZERO, BigDecimal.ZERO, UNIT_ID));
    }

    @Test
    void 构造_quantity为负数_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new BomLine(CHILD_ID, new BigDecimal("-1"), BigDecimal.ZERO, UNIT_ID));
    }

    @Test
    void 构造_quantity小数位超过6位_抛IllegalArgumentException() {
        // 0.1234567 有 7 位小数
        assertThrows(IllegalArgumentException.class,
                () -> new BomLine(CHILD_ID, new BigDecimal("0.1234567"), BigDecimal.ZERO, UNIT_ID));
    }

    @Test
    void 构造_scrapRate为null_抛NullPointerException() {
        assertThrows(NullPointerException.class,
                () -> new BomLine(CHILD_ID, BigDecimal.ONE, null, UNIT_ID));
    }

    @Test
    void 构造_scrapRate为负数_抛IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new BomLine(CHILD_ID, BigDecimal.ONE, new BigDecimal("-0.01"), UNIT_ID));
    }

    @Test
    void 构造_scrapRate等于1_抛IllegalArgumentException() {
        // scrapRate 必须 < 1
        assertThrows(IllegalArgumentException.class,
                () -> new BomLine(CHILD_ID, BigDecimal.ONE, BigDecimal.ONE, UNIT_ID));
    }

    @Test
    void 构造_scrapRate小数位超过6位_抛IllegalArgumentException() {
        // 0.9999999 有 7 位小数
        assertThrows(IllegalArgumentException.class,
                () -> new BomLine(CHILD_ID, BigDecimal.ONE, new BigDecimal("0.9999999"), UNIT_ID));
    }

    // ================================================================ grossQuantity 入参校验

    @Test
    void grossQuantity_入参为null_抛NullPointerException() {
        BomLine line = new BomLine(CHILD_ID, BigDecimal.ONE, BigDecimal.ZERO, UNIT_ID);
        assertThrows(NullPointerException.class, () -> line.grossQuantity(null));
    }

    @Test
    void grossQuantity_入参为0_抛IllegalArgumentException() {
        BomLine line = new BomLine(CHILD_ID, BigDecimal.ONE, BigDecimal.ZERO, UNIT_ID);
        assertThrows(IllegalArgumentException.class, () -> line.grossQuantity(BigDecimal.ZERO));
    }

    @Test
    void grossQuantity_入参为负数_抛IllegalArgumentException() {
        BomLine line = new BomLine(CHILD_ID, BigDecimal.ONE, BigDecimal.ZERO, UNIT_ID);
        assertThrows(IllegalArgumentException.class, () -> line.grossQuantity(new BigDecimal("-5")));
    }
}
