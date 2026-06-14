package com.sjherp.domain.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 采购线子账量回滚基元单测（M4-T07b 共享基元 3，设计真源 §2/§8）。
 *
 * <p>覆盖收货行已开票量回滚（{@code PurchaseReceiptLine.subtractInvoiced} 经
 * {@code PurchaseReceipt.reverseInvoiceLine}）与采购订单行到货量回滚
 * （{@code PurchaseOrderLine.subtractReceived} 经 {@code PurchaseOrder.reverseReceiveLine}）：
 * <ul>
 *   <li>正常回滚：累计量按 delta 递减；</li>
 *   <li>回滚多于已开票/到货量（回滚后 &lt; 0）拒绝（{@link IllegalArgumentException} 下溢守门）；</li>
 *   <li>delta ≤ 0 拒绝；</li>
 *   <li>聚合根状态守门：非 COMPLETED 收货单不可回滚开票量、非 APPROVED 订单不可回滚到货量
 *       （{@link IllegalStateException}）。</li>
 * </ul>
 *
 * <p>用 restore 工厂直接构造带累计量的聚合（不经服务）——纯领域基元，零依赖。
 */
class PurchaseLineRollbackTest {

    private static final String OPERATOR = "tester";
    private static final LocalDate D = LocalDate.of(2026, 6, 13);

    // ---------------------------------------------------------------
    // 收货行已开票量回滚（PurchaseReceiptLine.subtractInvoiced via reverseInvoiceLine）
    // ---------------------------------------------------------------

    /** 构造一张 COMPLETED 采购入库单，行1 收货 60、已开票 invoicedQty */
    private static PurchaseReceipt completedReceipt(String invoicedQty) {
        PurchaseReceiptLine line = PurchaseReceiptLine.restore(1L, 1, 1, 100L,
                new BigDecimal("60"), new BigDecimal("12.5"), new BigDecimal("750.00"),
                new BigDecimal(invoicedQty));
        return PurchaseReceipt.restore("PR-1", "PO-1", 10L, D, null, DocumentStatus.COMPLETED,
                List.of(line), OPERATOR);
    }

    @Test
    void 收货行开票量正常回滚_累计递减() {
        PurchaseReceipt receipt = completedReceipt("40");
        receipt.reverseInvoiceLine(1, new BigDecimal("40"));
        assertEqualsDecimal("0", receipt.getLines().get(0).getInvoicedQty());
    }

    @Test
    void 收货行开票量部分回滚_剩余可开票量恢复() {
        PurchaseReceipt receipt = completedReceipt("40");
        receipt.reverseInvoiceLine(1, new BigDecimal("15"));
        assertEqualsDecimal("25", receipt.getLines().get(0).getInvoicedQty());
        // 剩余可开票量 = 60 − 25 = 35
        assertEqualsDecimal("35", receipt.getLines().get(0).outstandingInvoiceableQty());
    }

    @Test
    void 收货行回滚多于已开票量_拒绝_下溢守门() {
        PurchaseReceipt receipt = completedReceipt("40");
        // 已开票 40，回滚 41 → 回滚后 -1 < 0 拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> receipt.reverseInvoiceLine(1, new BigDecimal("41")));
        assertEqualsDecimal("40", receipt.getLines().get(0).getInvoicedQty());   // 状态不变
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("超过已开票量"), ex.getMessage());
    }

    @Test
    void 收货行回滚量为零或负_拒绝() {
        PurchaseReceipt receipt = completedReceipt("40");
        assertThrows(IllegalArgumentException.class,
                () -> receipt.reverseInvoiceLine(1, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> receipt.reverseInvoiceLine(1, new BigDecimal("-1")));
    }

    @Test
    void 非COMPLETED收货单不可回滚开票量() {
        PurchaseReceiptLine line = PurchaseReceiptLine.restore(1L, 1, 1, 100L,
                new BigDecimal("60"), new BigDecimal("12.5"), new BigDecimal("750.00"),
                new BigDecimal("40"));
        PurchaseReceipt draft = PurchaseReceipt.restore("PR-1", "PO-1", 10L, D, null,
                DocumentStatus.DRAFT, List.of(line), OPERATOR);
        assertThrows(IllegalStateException.class,
                () -> draft.reverseInvoiceLine(1, new BigDecimal("10")));
    }

    // ---------------------------------------------------------------
    // 采购订单行到货量回滚（PurchaseOrderLine.subtractReceived via reverseReceiveLine）
    // ---------------------------------------------------------------

    /** 构造一张 APPROVED 采购订单，行1 订购 100、已到货 receivedQty */
    private static PurchaseOrder approvedOrder(String receivedQty) {
        PurchaseOrderLine line = PurchaseOrderLine.restore(1L, 1, 100L, new BigDecimal("100"),
                new BigDecimal("12.5"), new BigDecimal("1250.00"), new BigDecimal(receivedQty));
        return PurchaseOrder.restore("PO-1", 1L, D, null, DocumentStatus.APPROVED,
                List.of(line), OPERATOR);
    }

    @Test
    void 订单到货量正常回滚_剩余可收恢复() {
        PurchaseOrder order = approvedOrder("60");
        order.reverseReceiveLine(1, new BigDecimal("60"));
        assertEqualsDecimal("0", order.getLines().get(0).getReceivedQty());
        assertEqualsDecimal("100", order.getLines().get(0).outstandingQty());
    }

    @Test
    void 订单到货量回滚多于已到货量_拒绝_下溢守门() {
        PurchaseOrder order = approvedOrder("60");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> order.reverseReceiveLine(1, new BigDecimal("61")));
        assertEqualsDecimal("60", order.getLines().get(0).getReceivedQty());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("超过已到货量"), ex.getMessage());
    }

    @Test
    void 非APPROVED订单不可回滚到货量() {
        PurchaseOrderLine line = PurchaseOrderLine.restore(1L, 1, 100L, new BigDecimal("100"),
                new BigDecimal("12.5"), new BigDecimal("1250.00"), new BigDecimal("60"));
        PurchaseOrder completed = PurchaseOrder.restore("PO-1", 1L, D, null,
                DocumentStatus.COMPLETED, List.of(line), OPERATOR);
        assertThrows(IllegalStateException.class,
                () -> completed.reverseReceiveLine(1, new BigDecimal("10")));
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}
