package com.sjherp.domain.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;

/**
 * 凭证状态机单测（M4-T01，拆解 §3/§7）：过账 DRAFT→APPROVED 一步流转；过账后只可冲销不可改。
 *
 * <p>验证拆解 §3 状态映射：DRAFT=草稿、APPROVED=已过账、REVERSED=已冲销、CANCELLED=作废；
 * 不使用 EXECUTING/COMPLETED（凭证过账是原子记账动作）。
 */
class VoucherStateTest {

    private static final String DOC_NO = "VCH-202606-0001";
    private static final String PERIOD = "202606";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 13);
    private static final String OPERATOR = "tester";

    private Voucher draftVoucher() {
        return Voucher.create(DOC_NO, PERIOD, DATE, null, null, null, null, OPERATOR,
                List.of(line(1, "1001", "100.00", null), line(2, "6001", null, "100.00")));
    }

    // ----------------------------------------------------- 过账：DRAFT→APPROVED

    @Test
    void 过账_草稿到已过账一步流转() {
        Voucher voucher = draftVoucher();
        assertEquals(DocumentStatus.DRAFT, voucher.getStatus());

        voucher.post(OPERATOR);

        assertEquals(DocumentStatus.APPROVED, voucher.getStatus());
        assertEquals(OPERATOR, voucher.getUpdatedBy());
    }

    // ----------------------------------------------------- 重复过账拒绝

    @Test
    void 重复过账_抛非法状态流转() {
        Voucher voucher = draftVoucher();
        voucher.post(OPERATOR);
        // 已 APPROVED，再 post（→APPROVED）非法流转（APPROVED 只允许 EXECUTING/REVERSED）
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> voucher.post(OPERATOR));
        assertEquals(DocumentStatus.APPROVED, ex.getCurrentStatus());
        assertEquals(DocumentStatus.APPROVED, ex.getTargetStatus());
        // 状态保持 APPROVED 不变（流转被否决，模型不破碎）
        assertEquals(DocumentStatus.APPROVED, voucher.getStatus());
    }

    // ----------------------------------------------------- APPROVED→reverse 合法

    @Test
    void 已过账可冲销_流转到已冲销并记红字关联() {
        Voucher voucher = draftVoucher();
        voucher.post(OPERATOR);
        // 过账后只可冲销（APPROVED→REVERSED 合法），红字单号关联可审计
        voucher.reverse(OPERATOR, "VCH-202606-9999");

        assertEquals(DocumentStatus.REVERSED, voucher.getStatus());
        assertEquals("VCH-202606-9999", voucher.getReversedById());
    }

    @Test
    void 草稿不可直接冲销() {
        Voucher voucher = draftVoucher();
        // DRAFT 只允许 APPROVED/CANCELLED，直接 reverse 非法
        assertThrows(IllegalStateTransitionException.class,
                () -> voucher.reverse(OPERATOR, "VCH-202606-9999"));
    }

    // ----------------------------------------------------- DRAFT→cancel

    @Test
    void 草稿可作废() {
        Voucher voucher = draftVoucher();
        voucher.cancel(OPERATOR);
        assertEquals(DocumentStatus.CANCELLED, voucher.getStatus());
    }

    @Test
    void 已过账不可作废() {
        Voucher voucher = draftVoucher();
        voucher.post(OPERATOR);
        // APPROVED 不允许 CANCELLED（只允许 EXECUTING/REVERSED）
        assertThrows(IllegalStateTransitionException.class, () -> voucher.cancel(OPERATOR));
    }

    @Test
    void 已冲销为终态_不可再流转() {
        Voucher voucher = draftVoucher();
        voucher.post(OPERATOR);
        voucher.reverse(OPERATOR, "VCH-202606-9999");
        assertEquals(DocumentStatus.REVERSED, voucher.getStatus());
        // 终态：任何再流转均非法
        assertThrows(IllegalStateTransitionException.class, () -> voucher.post(OPERATOR));
        assertThrows(IllegalStateTransitionException.class, () -> voucher.cancel(OPERATOR));
    }

    // ----------------------------------------------------- 工具

    private static VoucherLine line(int lineNo, String accountCode, String debit, String credit) {
        return VoucherLine.create(lineNo, accountCode,
                debit == null ? null : new BigDecimal(debit),
                credit == null ? null : new BigDecimal(credit), null);
    }
}
