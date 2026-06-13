package com.sjherp.domain.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 凭证行值对象单测（M4-T01，拆解 §7）：行级不变式——恰好借或贷一方 &gt; 0、非负、最多 2 位小数。
 *
 * <p>纯领域校验，零外部依赖（CLAUDE.md 原则 5：金额一律 BigDecimal 禁 float/double）。
 */
class VoucherLineTest {

    private static final String CODE = "1001";

    // ----------------------------------------------------- 正常通过

    @Test
    void 单边借方正常通过() {
        VoucherLine line = VoucherLine.create(1, CODE, new BigDecimal("100.00"), null, "现金收入");

        assertEquals(1, line.getLineNo());
        assertEquals(CODE, line.getAccountCode());
        assertEqualsDecimal("100.00", line.getDebit());
        assertEqualsDecimal("0.00", line.getCredit());
        assertEquals("现金收入", line.getSummary());
    }

    @Test
    void 单边贷方正常通过() {
        VoucherLine line = VoucherLine.create(2, CODE, null, new BigDecimal("100.00"), null);

        assertEqualsDecimal("0.00", line.getDebit());
        assertEqualsDecimal("100.00", line.getCredit());
        assertNull(line.getSummary());
    }

    @Test
    void 借方为零贷方为空亦视作贷方零___属双边为零被拒() {
        // 一方传 0、另一方传 null（= 0）→ 两边都为 0 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, BigDecimal.ZERO, null, null));
    }

    // ----------------------------------------------------- 双边 > 0 拒绝

    @Test
    void 双边均大于零被拒() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, new BigDecimal("100.00"),
                        new BigDecimal("100.00"), null));
        assertContains(ex.getMessage(), "恰好借或贷");
    }

    // ----------------------------------------------------- 双边 = 0 拒绝

    @Test
    void 双边均为零被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    @Test
    void 双边均为空被拒() {
        // null 借 + null 贷 → 归一为 0 + 0 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, null, null, null));
    }

    // ----------------------------------------------------- 负数拒绝

    @Test
    void 借方为负被拒() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, new BigDecimal("-1.00"), null, null));
        assertContains(ex.getMessage(), "不能为负");
    }

    @Test
    void 贷方为负被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, null, new BigDecimal("-0.01"), null));
    }

    // ----------------------------------------------------- 超 2 位小数拒绝

    @Test
    void 借方超两位小数被拒() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, new BigDecimal("100.001"), null, null));
        assertContains(ex.getMessage(), "最多");
    }

    @Test
    void 贷方超两位小数被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, CODE, null, new BigDecimal("0.005"), null));
    }

    @Test
    void 恰好两位小数通过且补齐标度() {
        // 1 位小数也通过，归一后标度统一为 2
        VoucherLine line = VoucherLine.create(1, CODE, new BigDecimal("100.5"), null, null);
        assertEquals(2, line.getDebit().scale());
        assertEqualsDecimal("100.50", line.getDebit());
    }

    // ----------------------------------------------------- 行号 / 科目非法

    @Test
    void 行号小于一被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(0, CODE, new BigDecimal("1.00"), null, null));
    }

    @Test
    void 科目编码为空被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.create(1, "  ", new BigDecimal("1.00"), null, null));
    }

    // ----------------------------------------------------- restore 不重校

    @Test
    void restore_不重跑业务校验() {
        // 历史数据以入库时校验为准：restore 允许构造（即便看上去双边都有也不抛）
        VoucherLine restored = VoucherLine.restore(99L, 1, CODE, new BigDecimal("100.00"),
                new BigDecimal("100.00"), "历史行");
        assertEquals(99L, restored.getId());
        assertEqualsDecimal("100.00", restored.getDebit());
        assertEqualsDecimal("100.00", restored.getCredit());
    }

    // ----------------------------------------------------- 工具

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static void assertContains(String actual, String expectedFragment) {
        org.junit.jupiter.api.Assertions.assertTrue(actual != null && actual.contains(expectedFragment),
                "期望消息包含 [" + expectedFragment + "]，实际: " + actual);
    }
}
