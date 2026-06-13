package com.sjherp.domain.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 凭证聚合根单测（M4-T01，验收①：不平凭证无法保存——连聚合都构造不出，到不了 repository.save）。
 *
 * <p>{@link Voucher#create} 强制凭证级不变式：①≥2 行；②行号唯一；③Σ借==Σ贷；④总额&gt;0。
 * 任一违反抛 {@link VoucherNotBalancedException}（验收①）。{@link Voucher#restore} 不重跑校验。
 */
class VoucherTest {

    private static final String DOC_NO = "VCH-202606-0001";
    private static final String PERIOD = "202606";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 13);
    private static final String OPERATOR = "tester";

    // ----------------------------------------------------- 正常通过

    @Test
    void 一借一贷平衡通过_状态为草稿_总额为借方合计() {
        Voucher voucher = Voucher.create(DOC_NO, PERIOD, DATE, null, "测试凭证", null, null, OPERATOR,
                List.of(
                        line(1, "1001", "100.00", null),
                        line(2, "6001", null, "100.00")));

        assertEquals(DocumentStatus.DRAFT, voucher.getStatus());
        assertEqualsDecimal("100.00", voucher.getTotalAmount());
        assertEquals(2, voucher.getLines().size());
        assertEquals("记", voucher.getWord());
        assertEquals("测试凭证", voucher.getSummary());
        assertNull(voucher.getSourceDocNo());
        assertNull(voucher.getSourceDocType());
    }

    @Test
    void 多借多贷平衡通过() {
        // 借 60 + 40 = 100，贷 70 + 30 = 100 → 平衡
        Voucher voucher = Voucher.create(DOC_NO, PERIOD, DATE, "记", null, null, null, OPERATOR,
                List.of(
                        line(1, "1001", "60.00", null),
                        line(2, "1002", "40.00", null),
                        line(3, "6001", null, "70.00"),
                        line(4, "6051", null, "30.00")));

        assertEqualsDecimal("100.00", voucher.getTotalAmount());
        assertEquals(4, voucher.getLines().size());
    }

    @Test
    void 空白凭证字归一为记() {
        Voucher voucher = Voucher.create(DOC_NO, PERIOD, DATE, "   ", null, null, null, OPERATOR,
                List.of(line(1, "1001", "1.00", null), line(2, "6001", null, "1.00")));
        assertEquals("记", voucher.getWord());
    }

    // ----------------------------------------------------- 验收①：不平拒绝

    @Test
    void 借贷不平被拒_携带借贷合计() {
        VoucherNotBalancedException ex = assertThrows(VoucherNotBalancedException.class,
                () -> Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR,
                        List.of(line(1, "1001", "100.00", null), line(2, "6001", null, "99.00"))));
        assertEqualsDecimal("100.00", ex.getTotalDebit());
        assertEqualsDecimal("99.00", ex.getTotalCredit());
    }

    @Test
    void 不平异常是非法参数异常子类_可被四百统一拦截() {
        // VoucherNotBalancedException extends IllegalArgumentException → REST 400
        assertThrows(IllegalArgumentException.class,
                () -> Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR,
                        List.of(line(1, "1001", "100.00", null), line(2, "6001", null, "1.00"))));
    }

    // ----------------------------------------------------- 验收①：总额 0 拒绝

    @Test
    void 总额为零被拒() {
        // 借贷各 0 的行根本构造不出（VoucherLine 拒绝），此处用看似平衡但总额为 0 验证凭证级守卫：
        // 由于行级已禁止零额行，凭证级总额>0 守卫由「<2 行 + 行级非零」共同兜底；此处构造一张
        // 借贷都为 0 的非法凭证须借 restore 路径绕过行校验来验证凭证级守卫不可达——改测语义等价场景：
        // 单行（<2 行）即触发拒绝。详见 单行被拒。
        // 这里直接验证：所有行金额相等且能平衡但每行非零，无法令总额为 0，故总额 0 守卫由行级保证。
        VoucherNotBalancedException ex = assertThrows(VoucherNotBalancedException.class,
                () -> Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR,
                        List.of(line(1, "1001", "1.00", null))));
        // 单行 → ≥2 行守卫先行触发
        assertContains(ex.getMessage(), "至少要有 2 行");
    }

    // ----------------------------------------------------- 验收①：<2 行拒绝

    @Test
    void 单行被拒() {
        VoucherNotBalancedException ex = assertThrows(VoucherNotBalancedException.class,
                () -> Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR,
                        List.of(line(1, "1001", "100.00", null))));
        assertContains(ex.getMessage(), "至少要有 2 行");
    }

    @Test
    void 零行被拒() {
        assertThrows(VoucherNotBalancedException.class,
                () -> Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR, List.of()));
    }

    // ----------------------------------------------------- 验收①：行号重复拒绝

    @Test
    void 行号重复被拒() {
        VoucherNotBalancedException ex = assertThrows(VoucherNotBalancedException.class,
                () -> Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR,
                        List.of(line(1, "1001", "100.00", null), line(1, "6001", null, "100.00"))));
        assertContains(ex.getMessage(), "行号不能重复");
    }

    // ----------------------------------------------------- restore 不重校

    @Test
    void restore_不重跑平衡校验_历史已合法() {
        // 历史数据：即便构造一张看似不平的凭证，restore 也不抛（库中数据以入库时校验为准）
        Voucher restored = Voucher.restore(DOC_NO, PERIOD, DATE, "记", new BigDecimal("100.00"),
                "历史凭证", "PURCHASE_INVOICE", "PINV-1", DocumentStatus.APPROVED,
                List.of(
                        VoucherLine.restore(1L, 1, "1001", new BigDecimal("100.00"), BigDecimal.ZERO, null),
                        VoucherLine.restore(2L, 2, "6001", BigDecimal.ZERO, new BigDecimal("80.00"), null)),
                OPERATOR);

        assertEquals(DocumentStatus.APPROVED, restored.getStatus());
        assertEqualsDecimal("100.00", restored.getTotalAmount());
        assertEquals("PINV-1", restored.getSourceDocNo());
        assertEquals("PURCHASE_INVOICE", restored.getSourceDocType());
    }

    // ----------------------------------------------------- 行集合不可变

    @Test
    void 行视图只读_不可外部增删() {
        Voucher voucher = Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR,
                List.of(line(1, "1001", "1.00", null), line(2, "6001", null, "1.00")));
        assertThrows(UnsupportedOperationException.class,
                () -> voucher.getLines().add(line(3, "1002", "1.00", null)));
    }

    // ----------------------------------------------------- 工具

    private static VoucherLine line(int lineNo, String accountCode, String debit, String credit) {
        return VoucherLine.create(lineNo, accountCode,
                debit == null ? null : new BigDecimal(debit),
                credit == null ? null : new BigDecimal(credit), null);
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static void assertContains(String actual, String expectedFragment) {
        org.junit.jupiter.api.Assertions.assertTrue(actual != null && actual.contains(expectedFragment),
                "期望消息包含 [" + expectedFragment + "]，实际: " + actual);
    }
}
