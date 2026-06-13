package com.sjherp.domain.payable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * 应付账款聚合单测（M3-T07）：生成口径、金额边界、未核销余额、不可改（只可冲销）。
 */
class AccountsPayableTest {

    private static final long SUPPLIER = 1L;
    private static final LocalDate DUE = LocalDate.of(2026, 7, 13);
    private static final String OPERATOR = "tester";

    @Test
    void 生成应付为未核销_余额等于金额_已核销为零() {
        AccountsPayable ap = AccountsPayable.open(SUPPLIER, new BigDecimal("800.00"),
                "PINV-202606-0001", DUE, OPERATOR);

        assertEquals(SUPPLIER, ap.getSupplierId());
        assertEqualsDecimal("800.00", ap.getAmount());
        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
        assertEqualsDecimal("800.00", ap.outstandingAmount());
        assertEquals("PINV-202606-0001", ap.getSourceDocNo());
        assertEquals(DUE, ap.getDueDate());
    }

    @Test
    void 金额按两位HALF_UP规整() {
        // 0.005 → 0.01（HALF_UP）
        AccountsPayable ap = AccountsPayable.open(SUPPLIER, new BigDecimal("100.005"),
                "PINV-1", DUE, OPERATOR);
        assertEqualsDecimal("100.01", ap.getAmount());
    }

    @Test
    void 金额为零或负拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> AccountsPayable.open(SUPPLIER, BigDecimal.ZERO, "PINV-1", DUE, OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> AccountsPayable.open(SUPPLIER, new BigDecimal("-1"), "PINV-1", DUE, OPERATOR));
    }

    @Test
    void 重建保留状态与已核销金额() {
        AccountsPayable ap = AccountsPayable.restore(5L, SUPPLIER, new BigDecimal("500.00"),
                "PINV-1", DUE, PayableStatus.OPEN, new BigDecimal("0.00"), OPERATOR,
                java.time.Instant.now());
        assertEquals(5L, ap.getId());
        assertEqualsDecimal("500.00", ap.outstandingAmount());
    }

    @Test
    void id只能分配一次() {
        AccountsPayable ap = AccountsPayable.open(SUPPLIER, new BigDecimal("1.00"),
                "PINV-1", DUE, OPERATOR);
        ap.assignId(9L);
        assertThrows(IllegalStateException.class, () -> ap.assignId(10L));
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}
