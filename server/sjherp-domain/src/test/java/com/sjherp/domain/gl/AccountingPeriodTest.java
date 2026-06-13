package com.sjherp.domain.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 会计期间聚合根单测（M4-T01，拆解 §3/§7）：OPEN/CLOSED 两态、不可重复关账、重开（高敏）、格式不变式。
 */
class AccountingPeriodTest {

    private static final String OPERATOR = "tester";

    // ----------------------------------------------------- 开账

    @Test
    void 开账_初始为开启_拆解年月() {
        AccountingPeriod period = AccountingPeriod.open("202606", OPERATOR);

        assertEquals("202606", period.getPeriod());
        assertEquals(2026, period.getYear());
        assertEquals(6, period.getMonth());
        assertEquals(PeriodStatus.OPEN, period.getStatus());
        assertTrue(period.isOpen());
        assertNull(period.getClosedBy());
        assertNull(period.getClosedAt());
    }

    @Test
    void 账期格式非六位被拒() {
        assertThrows(IllegalArgumentException.class, () -> AccountingPeriod.open("2026-6", OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> AccountingPeriod.open("20266", OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> AccountingPeriod.open("abcdef", OPERATOR));
    }

    @Test
    void 月份越界被拒() {
        assertThrows(IllegalArgumentException.class, () -> AccountingPeriod.open("202600", OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> AccountingPeriod.open("202613", OPERATOR));
    }

    @Test
    void 账期为空被拒() {
        assertThrows(IllegalArgumentException.class, () -> AccountingPeriod.open("  ", OPERATOR));
    }

    // ----------------------------------------------------- 关账

    @Test
    void 关账_记录关账人与时间() {
        AccountingPeriod period = AccountingPeriod.open("202606", OPERATOR);
        period.close("accountant");

        assertEquals(PeriodStatus.CLOSED, period.getStatus());
        assertFalse(period.isOpen());
        assertEquals("accountant", period.getClosedBy());
        assertNotNull(period.getClosedAt());
    }

    @Test
    void 重复关账被拒() {
        AccountingPeriod period = AccountingPeriod.open("202606", OPERATOR);
        period.close(OPERATOR);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> period.close(OPERATOR));
        assertTrue(ex.getMessage().contains("已关闭"), ex.getMessage());
    }

    // ----------------------------------------------------- 重开（高敏）

    @Test
    void 重开_清空关账标记() {
        AccountingPeriod period = AccountingPeriod.open("202606", OPERATOR);
        period.close("accountant");
        period.reopen("boss");

        assertEquals(PeriodStatus.OPEN, period.getStatus());
        assertTrue(period.isOpen());
        assertNull(period.getClosedBy());
        assertNull(period.getClosedAt());
    }

    @Test
    void 开启状态重开被拒() {
        AccountingPeriod period = AccountingPeriod.open("202606", OPERATOR);
        assertThrows(IllegalStateException.class, () -> period.reopen(OPERATOR));
    }

    // ----------------------------------------------------- restore

    @Test
    void restore_重建不重校() {
        AccountingPeriod restored = AccountingPeriod.restore(7L, "202512", 2025, 12,
                PeriodStatus.CLOSED, "accountant", java.time.Instant.now(), "system",
                java.time.Instant.now(), "accountant", java.time.Instant.now());
        assertEquals(7L, restored.getId());
        assertEquals(PeriodStatus.CLOSED, restored.getStatus());
        assertFalse(restored.isOpen());
        assertEquals("accountant", restored.getClosedBy());
    }

    @Test
    void 审计摘要含账期与状态() {
        AccountingPeriod period = AccountingPeriod.open("202606", OPERATOR);
        String summary = period.auditSummary();
        assertTrue(summary.contains("202606"), summary);
        assertTrue(summary.contains("开启"), summary);
        assertEquals("202606", period.auditTargetCode());
    }
}
