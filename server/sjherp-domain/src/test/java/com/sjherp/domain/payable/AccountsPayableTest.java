package com.sjherp.domain.payable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.OverSettlementException;

/**
 * 应付账款聚合单测（M3-T07 生成口径 + M4-T03 核销分支）：生成口径、金额边界、未核销余额、
 * 不可改（只可冲销）；settle 全额/部分/累加至 SETTLED、超额拒绝、≤0 拒绝、归一精度。
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

    // ---------------- M4-T03 核销分支 ----------------

    private static AccountsPayable openAp(String amount) {
        return AccountsPayable.open(SUPPLIER, new BigDecimal(amount), "PINV-1", DUE, OPERATOR);
    }

    @Test
    void 全额一次核销_状态SETTLED_未核销余额归零() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("1000.00"));

        assertEquals(PayableStatus.SETTLED, ap.getStatus());
        assertEqualsDecimal("1000.00", ap.getSettledAmount());
        assertEqualsDecimal("0", ap.outstandingAmount());
        assertEqualsDecimal("1000.00", ap.getAmount());
    }

    @Test
    void 部分核销_状态PARTIAL_余额减少() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("300.00"));

        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        assertEqualsDecimal("300.00", ap.getSettledAmount());
        assertEqualsDecimal("700.00", ap.outstandingAmount());
    }

    @Test
    void 多次部分核销累加直到SETTLED() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("400.00"));
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        ap.settle(new BigDecimal("300.00"));
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        ap.settle(new BigDecimal("300.00"));
        assertEquals(PayableStatus.SETTLED, ap.getStatus());
        assertEqualsDecimal("1000.00", ap.getSettledAmount());
        assertEqualsDecimal("0", ap.outstandingAmount());
    }

    @Test
    void 超额核销_一次性超出_拒绝且状态不变() {
        AccountsPayable ap = openAp("1000.00");
        assertThrows(OverSettlementException.class, () -> ap.settle(new BigDecimal("1000.01")));
        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
        assertEqualsDecimal("1000.00", ap.outstandingAmount());
    }

    @Test
    void 超额核销_累加触发超出_拒绝且保留已核销额() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("800.00"));
        assertThrows(OverSettlementException.class, () -> ap.settle(new BigDecimal("200.01")));
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        assertEqualsDecimal("800.00", ap.getSettledAmount());
        assertEqualsDecimal("200.00", ap.outstandingAmount());
    }

    @Test
    void 已SETTLED再核销_必触发超额拒绝() {
        AccountsPayable ap = openAp("500.00");
        ap.settle(new BigDecimal("500.00"));
        assertEquals(PayableStatus.SETTLED, ap.getStatus());
        assertThrows(OverSettlementException.class, () -> ap.settle(new BigDecimal("0.01")));
        assertEquals(PayableStatus.SETTLED, ap.getStatus());
        assertEqualsDecimal("500.00", ap.getSettledAmount());
    }

    @Test
    void 核销金额为零或负_拒绝且非OverSettlement() {
        AccountsPayable ap = openAp("100.00");
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> ap.settle(BigDecimal.ZERO));
        assertFalse(zero instanceof OverSettlementException);
        IllegalArgumentException neg = assertThrows(IllegalArgumentException.class,
                () -> ap.settle(new BigDecimal("-1")));
        assertFalse(neg instanceof OverSettlementException);
        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
    }

    @Test
    void 核销金额为null_拒绝() {
        AccountsPayable ap = openAp("100.00");
        assertThrows(IllegalArgumentException.class, () -> ap.settle(null));
    }

    @Test
    void 核销金额三位小数HALF_UP归一后入账() {
        AccountsPayable ap = openAp("100.00");
        // 33.335 → 33.34（HALF_UP）
        ap.settle(new BigDecimal("33.335"));
        assertEqualsDecimal("33.34", ap.getSettledAmount());
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        assertEqualsDecimal("66.66", ap.outstandingAmount());
    }

    @Test
    void 归一后正好等于原始金额_SETTLED() {
        AccountsPayable ap = openAp("100.00");
        // 99.999 → 100.00（HALF_UP），恰等于原始金额 → SETTLED 而非超额
        ap.settle(new BigDecimal("99.999"));
        assertEquals(PayableStatus.SETTLED, ap.getStatus());
        assertEqualsDecimal("100.00", ap.getSettledAmount());
        assertEqualsDecimal("0", ap.outstandingAmount());
    }

    // ---------------- M4-T07b 冲销分支（canBeReversed / markReversed） ----------------

    @Test
    void 未核销且OPEN_可冲销() {
        AccountsPayable ap = openAp("1000.00");
        assertTrue(ap.canBeReversed());
    }

    @Test
    void 已部分核销_不可冲销() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("300.00"));
        assertFalse(ap.canBeReversed());
    }

    @Test
    void 已全额SETTLED_不可冲销() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("1000.00"));
        assertFalse(ap.canBeReversed());
    }

    @Test
    void markReversed_满足条件_状态转REVERSED() {
        AccountsPayable ap = openAp("1000.00");
        ap.markReversed(OPERATOR);
        assertEquals(PayableStatus.REVERSED, ap.getStatus());
        assertFalse(ap.canBeReversed());   // 已冲销不可再冲
    }

    @Test
    void markReversed_已部分核销_抛IllegalState且非OverSettlement_状态不变() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("300.00"));
        // markReversed 抛 IllegalStateException（不可冲），区别于 settle 超额的 OverSettlementException
        // （后者是 IllegalArgumentException 子类）——二者类型不相关，此处校验抛的是 IllegalState 即可。
        assertThrows(IllegalStateException.class, () -> ap.markReversed(OPERATOR));
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
    }

    @Test
    void markReversed_已SETTLED_抛IllegalState() {
        AccountsPayable ap = openAp("500.00");
        ap.settle(new BigDecimal("500.00"));
        assertThrows(IllegalStateException.class, () -> ap.markReversed(OPERATOR));
        assertEquals(PayableStatus.SETTLED, ap.getStatus());
    }

    @Test
    void markReversed_重复冲销_第二次抛IllegalState() {
        AccountsPayable ap = openAp("100.00");
        ap.markReversed(OPERATOR);
        assertThrows(IllegalStateException.class, () -> ap.markReversed(OPERATOR));
        assertEquals(PayableStatus.REVERSED, ap.getStatus());
    }

    // ---------------- M4-T07c 核销反向分支（unsettle，镜像应收） ----------------

    @Test
    void unsettle_全额回退至0_状态回OPEN_余额恢复() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("1000.00"));
        assertEquals(PayableStatus.SETTLED, ap.getStatus());

        ap.unsettle(new BigDecimal("1000.00"));
        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
        assertEqualsDecimal("1000.00", ap.outstandingAmount());
        assertEqualsDecimal("1000.00", ap.getAmount());
    }

    @Test
    void unsettle_部分回退_仍有已核销额_状态PARTIAL() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("1000.00"));   // SETTLED
        ap.unsettle(new BigDecimal("400.00"));
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        assertEqualsDecimal("600.00", ap.getSettledAmount());
        assertEqualsDecimal("400.00", ap.outstandingAmount());
    }

    @Test
    void unsettle_从PARTIAL退到0_状态回OPEN() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("300.00"));
        ap.unsettle(new BigDecimal("300.00"));
        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
    }

    @Test
    void unsettle_下溢_退额超过已核销额_拒绝且状态不变() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("300.00"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ap.unsettle(new BigDecimal("300.01")));
        assertTrue(ex.getMessage().contains("超过已核销额"), ex.getMessage());
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        assertEqualsDecimal("300.00", ap.getSettledAmount());
    }

    @Test
    void unsettle_对未核销OPEN子账_任何正额都下溢拒绝() {
        AccountsPayable ap = openAp("1000.00");
        assertThrows(IllegalArgumentException.class, () -> ap.unsettle(new BigDecimal("0.01")));
        assertEquals(PayableStatus.OPEN, ap.getStatus());
    }

    @Test
    void unsettle_金额为零或负或null_拒绝() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("500.00"));
        assertThrows(IllegalArgumentException.class, () -> ap.unsettle(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ap.unsettle(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> ap.unsettle(null));
        assertEqualsDecimal("500.00", ap.getSettledAmount());
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
    }

    @Test
    void unsettle_对REVERSED子账_抛IllegalState() {
        AccountsPayable ap = openAp("1000.00");
        ap.markReversed(OPERATOR);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ap.unsettle(new BigDecimal("1.00")));
        assertTrue(ex.getMessage().contains("已冲销"), ex.getMessage());
        assertEquals(PayableStatus.REVERSED, ap.getStatus());
    }

    @Test
    void unsettle_金额三位小数HALF_UP归一后扣回() {
        AccountsPayable ap = openAp("100.00");
        ap.settle(new BigDecimal("100.00"));
        ap.unsettle(new BigDecimal("33.335")); // → 33.34
        assertEqualsDecimal("66.66", ap.getSettledAmount());
        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
    }

    @Test
    void settle与unsettle对称_回到初态() {
        AccountsPayable ap = openAp("1000.00");
        ap.settle(new BigDecimal("700.00"));
        ap.unsettle(new BigDecimal("700.00"));
        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
        assertEqualsDecimal("1000.00", ap.outstandingAmount());
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}
