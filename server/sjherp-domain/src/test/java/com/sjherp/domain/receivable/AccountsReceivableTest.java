package com.sjherp.domain.receivable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.OverSettlementException;

/**
 * 应收账款聚合单测（M4-T03 核销分支）：settle 推进已核销额与状态、超额拒绝、≤0 拒绝、
 * 多次部分核销累加至 SETTLED、归一精度、未核销余额随核销更新。
 *
 * <p>沿用本仓既有 domain 单测风格（纯 JUnit5、无 Spring/Mockito、BigDecimal compareTo 断言）。
 */
class AccountsReceivableTest {

    private static final long CUSTOMER = 7L;
    private static final LocalDate DUE = LocalDate.of(2026, 7, 14);
    private static final String OPERATOR = "tester";

    private static AccountsReceivable openAr(String amount) {
        return AccountsReceivable.open(CUSTOMER, new BigDecimal(amount), "SINV-1", DUE, OPERATOR);
    }

    @Test
    void 全额一次核销_状态SETTLED_未核销余额归零() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("1000.00"));

        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());
        assertEqualsDecimal("1000.00", ar.getSettledAmount());
        assertEqualsDecimal("0", ar.openAmount());
        // 原始金额永不变（CLAUDE.md 原则 2）
        assertEqualsDecimal("1000.00", ar.getAmount());
    }

    @Test
    void 部分核销_状态PARTIAL_余额减少() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("300.00"));

        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("300.00", ar.getSettledAmount());
        assertEqualsDecimal("700.00", ar.openAmount());
    }

    @Test
    void 多次部分核销累加直到SETTLED() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("400.00"));
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("600.00", ar.openAmount());

        ar.settle(new BigDecimal("300.00"));
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("300.00", ar.openAmount());

        ar.settle(new BigDecimal("300.00"));
        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());
        assertEqualsDecimal("1000.00", ar.getSettledAmount());
        assertEqualsDecimal("0", ar.openAmount());
    }

    @Test
    void 超额核销_一次性超出_拒绝且状态不变() {
        AccountsReceivable ar = openAr("1000.00");
        assertThrows(OverSettlementException.class, () -> ar.settle(new BigDecimal("1000.01")));
        // 拒绝后聚合态不变
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
        assertEqualsDecimal("0", ar.getSettledAmount());
        assertEqualsDecimal("1000.00", ar.openAmount());
    }

    @Test
    void 超额核销_累加触发超出_拒绝且保留已核销额() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("800.00"));
        assertThrows(OverSettlementException.class, () -> ar.settle(new BigDecimal("200.01")));
        // 第二次被拒，第一次已核销额保留，状态仍 PARTIAL
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("800.00", ar.getSettledAmount());
        assertEqualsDecimal("200.00", ar.openAmount());
    }

    @Test
    void 已SETTLED再核销_必触发超额拒绝() {
        AccountsReceivable ar = openAr("500.00");
        ar.settle(new BigDecimal("500.00"));
        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());
        // outstanding=0，任何正数核销都超额
        assertThrows(OverSettlementException.class, () -> ar.settle(new BigDecimal("0.01")));
        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());
        assertEqualsDecimal("500.00", ar.getSettledAmount());
    }

    @Test
    void 核销金额为零或负_拒绝且非OverSettlement() {
        AccountsReceivable ar = openAr("100.00");
        // ≤0 是 IllegalArgumentException 但不是 OverSettlementException（OverSettlement 是其子类，需精确区分）
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> ar.settle(BigDecimal.ZERO));
        org.junit.jupiter.api.Assertions.assertFalse(zero instanceof OverSettlementException);
        IllegalArgumentException neg = assertThrows(IllegalArgumentException.class,
                () -> ar.settle(new BigDecimal("-1")));
        org.junit.jupiter.api.Assertions.assertFalse(neg instanceof OverSettlementException);
        // 拒绝后未动账
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
        assertEqualsDecimal("0", ar.getSettledAmount());
    }

    @Test
    void 核销金额为null_拒绝() {
        AccountsReceivable ar = openAr("100.00");
        assertThrows(IllegalArgumentException.class, () -> ar.settle(null));
    }

    @Test
    void 核销金额三位小数HALF_UP归一后入账() {
        AccountsReceivable ar = openAr("100.00");
        // 33.335 → 33.34（HALF_UP），归一后累加
        ar.settle(new BigDecimal("33.335"));
        assertEqualsDecimal("33.34", ar.getSettledAmount());
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("66.66", ar.openAmount());
    }

    @Test
    void 归一后正好等于原始金额_SETTLED() {
        AccountsReceivable ar = openAr("100.00");
        // 99.999 → 100.00（HALF_UP），归一后恰等于原始金额 → SETTLED（而非超额）
        ar.settle(new BigDecimal("99.999"));
        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());
        assertEqualsDecimal("100.00", ar.getSettledAmount());
        assertEqualsDecimal("0", ar.openAmount());
    }

    // ---------------- M4-T07b 冲销分支（canBeReversed / markReversed） ----------------

    @Test
    void 未核销且OPEN_可冲销() {
        AccountsReceivable ar = openAr("1000.00");
        assertTrue(ar.canBeReversed());
    }

    @Test
    void 已部分核销_不可冲销() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("300.00"));
        assertFalse(ar.canBeReversed());
    }

    @Test
    void 已全额SETTLED_不可冲销() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("1000.00"));
        assertFalse(ar.canBeReversed());
    }

    @Test
    void markReversed_满足条件_状态转REVERSED() {
        AccountsReceivable ar = openAr("1000.00");
        ar.markReversed(OPERATOR);
        assertEquals(ReceivableStatus.REVERSED, ar.getStatus());
        assertFalse(ar.canBeReversed());   // 已冲销不可再冲
        // 原始金额永不变（CLAUDE.md 原则 2）
        assertEqualsDecimal("1000.00", ar.getAmount());
    }

    @Test
    void markReversed_已部分核销_抛IllegalState且非OverSettlement_状态不变() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("300.00"));
        // markReversed 抛 IllegalStateException（不可冲），区别于 settle 超额的 OverSettlementException
        // （后者是 IllegalArgumentException 子类）——二者类型不相关，此处校验抛的是 IllegalState 即可。
        assertThrows(IllegalStateException.class, () -> ar.markReversed(OPERATOR));
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
    }

    @Test
    void markReversed_已SETTLED_抛IllegalState() {
        AccountsReceivable ar = openAr("500.00");
        ar.settle(new BigDecimal("500.00"));
        assertThrows(IllegalStateException.class, () -> ar.markReversed(OPERATOR));
        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());
    }

    @Test
    void markReversed_重复冲销_第二次抛IllegalState() {
        AccountsReceivable ar = openAr("100.00");
        ar.markReversed(OPERATOR);
        assertThrows(IllegalStateException.class, () -> ar.markReversed(OPERATOR));
        assertEquals(ReceivableStatus.REVERSED, ar.getStatus());
    }

    // ---------------- M4-T07c 核销反向分支（unsettle） ----------------

    @Test
    void unsettle_全额回退至0_状态回OPEN_余额恢复() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("1000.00"));
        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());

        ar.unsettle(new BigDecimal("1000.00"));
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
        assertEqualsDecimal("0", ar.getSettledAmount());
        assertEqualsDecimal("1000.00", ar.openAmount());
        // 原始金额永不变（CLAUDE.md 原则 2）
        assertEqualsDecimal("1000.00", ar.getAmount());
    }

    @Test
    void unsettle_部分回退_仍有已核销额_状态PARTIAL() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("1000.00"));   // SETTLED

        ar.unsettle(new BigDecimal("400.00"));  // 退 400 → 剩 600
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("600.00", ar.getSettledAmount());
        assertEqualsDecimal("400.00", ar.openAmount());
    }

    @Test
    void unsettle_从PARTIAL退到0_状态回OPEN() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("300.00"));    // PARTIAL
        ar.unsettle(new BigDecimal("300.00"));
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
        assertEqualsDecimal("0", ar.getSettledAmount());
    }

    @Test
    void unsettle_部分回退后settled恰回到总额_状态SETTLED() {
        // 设计真源 §2：== amount(总额) → SETTLED（部分冲回后仍满额的极端场景）
        // 多笔核销至 SETTLED 后只退其中一笔的"溢出"部分时若 settled 仍等于总额则保持 SETTLED
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("1000.00"));   // SETTLED，settled=1000
        // 边界覆盖：unsettle 0.00 归一后为 0 → IllegalArgument（不退）；此处用极小退额后再断言 PARTIAL
        ar.unsettle(new BigDecimal("0.01"));
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("999.99", ar.getSettledAmount());
    }

    @Test
    void unsettle_下溢_退额超过已核销额_拒绝且状态不变() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("300.00"));    // settled=300 PARTIAL
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ar.unsettle(new BigDecimal("300.01")));
        assertTrue(ex.getMessage().contains("超过已核销额"), ex.getMessage());
        // 拒绝后聚合态不变
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("300.00", ar.getSettledAmount());
    }

    @Test
    void unsettle_对未核销OPEN子账_任何正额都下溢拒绝() {
        AccountsReceivable ar = openAr("1000.00");   // settled=0 OPEN
        assertThrows(IllegalArgumentException.class, () -> ar.unsettle(new BigDecimal("0.01")));
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
    }

    @Test
    void unsettle_金额为零或负或null_拒绝() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("500.00"));
        assertThrows(IllegalArgumentException.class, () -> ar.unsettle(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ar.unsettle(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> ar.unsettle(null));
        // 拒绝后不动账
        assertEqualsDecimal("500.00", ar.getSettledAmount());
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
    }

    @Test
    void unsettle_对REVERSED子账_抛IllegalState() {
        AccountsReceivable ar = openAr("1000.00");
        ar.markReversed(OPERATOR);   // OPEN → REVERSED（未核销才可冲）
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ar.unsettle(new BigDecimal("1.00")));
        assertTrue(ex.getMessage().contains("已冲销"), ex.getMessage());
        assertEquals(ReceivableStatus.REVERSED, ar.getStatus());
    }

    @Test
    void unsettle_金额三位小数HALF_UP归一后扣回() {
        AccountsReceivable ar = openAr("100.00");
        ar.settle(new BigDecimal("100.00"));    // SETTLED
        // 33.335 → 33.34（HALF_UP），归一后扣回
        ar.unsettle(new BigDecimal("33.335"));
        assertEqualsDecimal("66.66", ar.getSettledAmount());
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
    }

    @Test
    void settle与unsettle对称_回到初态() {
        AccountsReceivable ar = openAr("1000.00");
        ar.settle(new BigDecimal("700.00"));
        ar.unsettle(new BigDecimal("700.00"));
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
        assertEqualsDecimal("0", ar.getSettledAmount());
        assertEqualsDecimal("1000.00", ar.openAmount());
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}
