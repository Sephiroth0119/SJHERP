package com.sjherp.domain.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * 核销记录聚合单测（M4-T03）：工厂 amount>0 校验、归一精度、字段不可变（无改/删方法）、
 * id 单次分配、AuditTarget 摘要正确、restore 不重跑业务校验。
 */
class SettlementRecordTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 14);
    private static final String SRC = "SINV-202606-0001";
    private static final String OPERATOR = "tester";

    private static SettlementRecord record(String amount, String paymentDocNo) {
        return SettlementRecord.record(SettlementType.RECEIVABLE, 5L, SRC,
                new BigDecimal(amount), DATE, paymentDocNo, OPERATOR);
    }

    @Test
    void 工厂建记录_字段落位_id初始为空_paymentDocNo可空() {
        SettlementRecord r = record("300.00", null);

        assertNull(r.getId(), "落库前 id 应为 null");
        assertEquals(SettlementType.RECEIVABLE, r.getType());
        assertEquals(5L, r.getTargetId());
        assertEquals(SRC, r.getTargetSourceDocNo());
        assertEqualsDecimal("300.00", r.getAmount());
        assertEquals(DATE, r.getSettlementDate());
        assertNull(r.getPaymentDocNo(), "T03 收付款单号恒空");
        assertEquals(OPERATOR, r.getCreatedBy());
        org.junit.jupiter.api.Assertions.assertNotNull(r.getCreatedAt());
    }

    @Test
    void 金额为零或负或null_拒绝() {
        assertThrows(IllegalArgumentException.class, () -> record("0", null));
        assertThrows(IllegalArgumentException.class, () -> record("-1", null));
        assertThrows(IllegalArgumentException.class, () -> SettlementRecord.record(
                SettlementType.PAYABLE, 1L, SRC, null, DATE, null, OPERATOR));
    }

    @Test
    void 金额三位小数HALF_UP归一为两位() {
        // 33.335 → 33.34
        SettlementRecord r = record("33.335", null);
        assertEqualsDecimal("33.34", r.getAmount());
        assertEquals(2, r.getAmount().scale(), "归一为 2 位标度");
    }

    @Test
    void id只能分配一次() {
        SettlementRecord r = record("100.00", null);
        r.assignId(9L);
        assertEquals(9L, r.getId());
        assertThrows(IllegalStateException.class, () -> r.assignId(10L));
    }

    @Test
    void restore重建保留字段且不重跑校验() {
        Instant createdAt = Instant.parse("2026-06-14T01:02:03.456Z");
        SettlementRecord r = SettlementRecord.restore(42L, SettlementType.PAYABLE, 7L, "PINV-9",
                new BigDecimal("88.88"), DATE, "PAY-1", "acct", createdAt);
        assertEquals(42L, r.getId());
        assertEquals(SettlementType.PAYABLE, r.getType());
        assertEquals(7L, r.getTargetId());
        assertEquals("PINV-9", r.getTargetSourceDocNo());
        assertEqualsDecimal("88.88", r.getAmount());
        assertEquals("PAY-1", r.getPaymentDocNo());
        assertEquals("acct", r.getCreatedBy());
        assertEquals(createdAt, r.getCreatedAt());
    }

    @Test
    void 无任何改删方法_只读访问器() {
        // 反射断言聚合不暴露 set*/delete*/remove*/update* 等可变方法（只追加财务记录，原则 2）
        for (var m : SettlementRecord.class.getDeclaredMethods()) {
            String name = m.getName();
            assertTrue(!name.startsWith("set") && !name.startsWith("delete")
                            && !name.startsWith("remove") && !name.startsWith("update"),
                    "核销记录不应暴露可变方法: " + name);
        }
    }

    @Test
    void auditTarget摘要含关键字段_paymentDocNo空显示破折号() {
        SettlementRecord r = record("300.00", null);
        r.assignId(11L);
        assertEquals(Long.valueOf(11L), r.auditTargetId());
        assertEquals(SRC, r.auditTargetCode());
        String summary = r.auditSummary();
        assertTrue(summary.contains(SettlementType.RECEIVABLE.label()), "摘要含类型中文名: " + summary);
        assertTrue(summary.contains("目标id=5"), "摘要含目标 id: " + summary);
        assertTrue(summary.contains(SRC), "摘要含来源单据: " + summary);
        assertTrue(summary.contains("300.00"), "摘要含核销金额: " + summary);
        assertTrue(summary.contains(DATE.toString()), "摘要含核销日: " + summary);
        // paymentDocNo 空 → AuditTarget.text 输出 "-"
        assertTrue(summary.contains("收付款单=-"), "空收付款单号显示破折号: " + summary);
    }

    @Test
    void auditTarget摘要_有paymentDocNo时显示其值() {
        SettlementRecord r = record("300.00", "PAY-2026-0007");
        assertTrue(r.auditSummary().contains("收付款单=PAY-2026-0007"));
    }

    // ---------------- M4-T07c 反向核销记录工厂（recordReversal，负额） ----------------

    private static SettlementRecord reversal(String amount, String paymentDocNo) {
        return SettlementRecord.recordReversal(SettlementType.RECEIVABLE, 5L, SRC,
                new BigDecimal(amount), DATE, paymentDocNo, OPERATOR);
    }

    @Test
    void recordReversal_负额记录_字段落位() {
        SettlementRecord r = reversal("-300.00", "RCPT-1");
        assertNull(r.getId(), "落库前 id 应为 null");
        assertEquals(SettlementType.RECEIVABLE, r.getType());
        assertEquals(5L, r.getTargetId());
        assertEquals(SRC, r.getTargetSourceDocNo());
        assertEqualsDecimal("-300.00", r.getAmount());
        assertTrue(r.getAmount().signum() < 0, "反向核销记录金额必须为负");
        assertEquals("RCPT-1", r.getPaymentDocNo(), "反查锚点=被冲销收付款单号");
        assertEquals(OPERATOR, r.getCreatedBy());
        org.junit.jupiter.api.Assertions.assertNotNull(r.getCreatedAt());
    }

    @Test
    void recordReversal_金额为零或正或null_拒绝() {
        // 反向记录必须 < 0；0 / 正额 / null 均拒（正额走 record，负额走 recordReversal，边界互斥）
        assertThrows(IllegalArgumentException.class, () -> reversal("0", "RCPT-1"));
        assertThrows(IllegalArgumentException.class, () -> reversal("1", "RCPT-1"));
        assertThrows(IllegalArgumentException.class, () -> SettlementRecord.recordReversal(
                SettlementType.PAYABLE, 1L, SRC, null, DATE, "PAYV-1", OPERATOR));
    }

    @Test
    void recordReversal_负三位小数HALF_UP归一为两位() {
        // -33.335 → -33.34（HALF_UP 对负数同样归一到 2 位）
        SettlementRecord r = reversal("-33.335", "RCPT-1");
        assertEqualsDecimal("-33.34", r.getAmount());
        assertEquals(2, r.getAmount().scale(), "归一为 2 位标度");
    }

    @Test
    void 正向record仍强制正额_不接受负额() {
        // 边界对照：正向工厂 record 仍校验 > 0，负额走它必拒（与 recordReversal 互斥）
        assertThrows(IllegalArgumentException.class, () -> record("-1", "RCPT-1"));
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}
