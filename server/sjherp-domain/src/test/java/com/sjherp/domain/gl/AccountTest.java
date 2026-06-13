package com.sjherp.domain.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * 会计科目聚合根单测（M4-T01，拆解 §3/§7）：两态档案（启用/停用）、预置科目守门（禁停用）、不可物理删除。
 */
class AccountTest {

    private static final String OPERATOR = "tester";

    // ----------------------------------------------------- 建档

    @Test
    void 新建科目_默认启用且非预置() {
        Account account = Account.create("6601", "销售费用", AccountType.PROFIT_LOSS,
                BalanceDirection.DEBIT, null, 1, true, OPERATOR);

        assertEquals("6601", account.getCode());
        assertEquals("销售费用", account.getName());
        assertEquals(AccountType.PROFIT_LOSS, account.getType());
        assertEquals(BalanceDirection.DEBIT, account.getBalanceDir());
        assertEquals(1, account.getLevel());
        assertTrue(account.isLeaf());
        assertTrue(account.isEnabled());
        assertFalse(account.isPreset());
    }

    @Test
    void 编码为空被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> Account.create("  ", "名称", AccountType.ASSET, BalanceDirection.DEBIT,
                        null, 1, true, OPERATOR));
    }

    @Test
    void 名称为空被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> Account.create("1001", " ", AccountType.ASSET, BalanceDirection.DEBIT,
                        null, 1, true, OPERATOR));
    }

    @Test
    void 层级小于一被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> Account.create("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT,
                        null, 0, true, OPERATOR));
    }

    // ----------------------------------------------------- 启停

    @Test
    void 启用停用切换() {
        Account account = Account.create("6601", "销售费用", AccountType.PROFIT_LOSS,
                BalanceDirection.DEBIT, null, 1, true, OPERATOR);
        account.disable("ops");
        assertFalse(account.isEnabled());
        assertEquals("ops", account.getUpdatedBy());

        account.enable("ops2");
        assertTrue(account.isEnabled());
        assertEquals("ops2", account.getUpdatedBy());
    }

    @Test
    void 重复停用被拒() {
        Account account = Account.create("6601", "销售费用", AccountType.PROFIT_LOSS,
                BalanceDirection.DEBIT, null, 1, true, OPERATOR);
        account.disable(OPERATOR);
        assertThrows(IllegalArgumentException.class, () -> account.disable(OPERATOR));
    }

    @Test
    void 重复启用被拒() {
        Account account = Account.create("6601", "销售费用", AccountType.PROFIT_LOSS,
                BalanceDirection.DEBIT, null, 1, true, OPERATOR);
        // 新建即启用，再启用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> account.enable(OPERATOR));
    }

    // ----------------------------------------------------- 预置科目守门

    @Test
    void 预置科目不可停用() {
        // 预置科目经 restore 重建（is_preset=true），disable 直接拒绝（守门口径稳定）
        Account preset = Account.restore(1L, "1001", "库存现金", AccountType.ASSET,
                BalanceDirection.DEBIT, null, 1, true, true, true, "system",
                Instant.now(), "system", Instant.now());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> preset.disable(OPERATOR));
        assertTrue(ex.getMessage().contains("预置科目"), ex.getMessage());
        assertTrue(preset.isEnabled());
    }

    @Test
    void restore_重建不重校() {
        Account restored = Account.restore(5L, "222101", "应交税费—应交增值税", AccountType.LIABILITY,
                BalanceDirection.CREDIT, "2221", 2, true, false, true, "system",
                Instant.now(), "ops", Instant.now());
        assertEquals(5L, restored.getId());
        assertEquals("2221", restored.getParentCode());
        assertEquals(2, restored.getLevel());
        assertFalse(restored.isEnabled());
        assertTrue(restored.isPreset());
    }

    // ----------------------------------------------------- 审计摘要

    @Test
    void 审计摘要含编码名称类别() {
        Account account = Account.create("1001", "库存现金", AccountType.ASSET,
                BalanceDirection.DEBIT, null, 1, true, OPERATOR);
        String summary = account.auditSummary();
        assertTrue(summary.contains("1001"), summary);
        assertTrue(summary.contains("库存现金"), summary);
        assertTrue(summary.contains("资产"), summary);
        assertEquals("1001", account.auditTargetCode());
    }
}
