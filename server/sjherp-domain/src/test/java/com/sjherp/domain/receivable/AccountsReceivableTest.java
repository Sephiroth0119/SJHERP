package com.sjherp.domain.receivable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}
