package com.sjherp.domain.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;

/**
 * 销售线子账量回滚基元单测（M4-T07b 共享基元 3，设计真源 §2/§8）。
 *
 * <p>覆盖出库行已开票量回滚（{@code SalesDeliveryLine.subtractInvoiced} 经
 * {@code SalesDelivery.reverseInvoiceLine}）与销售订单行发货量回滚
 * （{@code SalesOrderLine.subtractDelivered}，public 与 addDelivered 对齐）：
 * <ul>
 *   <li>正常回滚：累计量按 delta 递减；</li>
 *   <li>回滚多于已开票/发货量（回滚后 &lt; 0）拒绝（{@link IllegalArgumentException} 下溢守门）；</li>
 *   <li>delta ≤ 0 拒绝；</li>
 *   <li>聚合根状态守门：非 COMPLETED 出库单不可回滚开票量（{@link IllegalStateException}）。</li>
 * </ul>
 *
 * <p>用 restore 工厂直接构造带累计量的聚合（不经服务）——纯领域基元，零依赖。
 */
class SalesLineRollbackTest {

    private static final String OPERATOR = "tester";
    private static final LocalDate D = LocalDate.of(2026, 6, 13);

    // ---------------------------------------------------------------
    // 出库行已开票量回滚（SalesDeliveryLine.subtractInvoiced via reverseInvoiceLine）
    // ---------------------------------------------------------------

    /** 构造一张 COMPLETED 销售出库单，行1 发货 70、已开票 invoicedQty、COGS 700.00 */
    private static SalesDelivery completedDelivery(String invoicedQty) {
        SalesDeliveryLine line = SalesDeliveryLine.restore(1L, 1, 1, 100L, new BigDecimal("70"),
                new BigDecimal("700.00"), new BigDecimal(invoicedQty));
        return SalesDelivery.restore("SD-1", "SO-1", 1L, null, DocumentStatus.COMPLETED,
                List.of(line), OPERATOR);
    }

    @Test
    void 出库行开票量正常回滚_累计递减() {
        SalesDelivery delivery = completedDelivery("40");
        delivery.reverseInvoiceLine(1, new BigDecimal("40"));
        assertEqualsDecimal("0", delivery.getLines().get(0).getInvoicedQty());
        // 剩余可开票量恢复 70
        assertEqualsDecimal("70", delivery.getLines().get(0).outstandingInvoiceableQty());
    }

    @Test
    void 出库行开票量部分回滚_剩余可开票量恢复() {
        SalesDelivery delivery = completedDelivery("40");
        delivery.reverseInvoiceLine(1, new BigDecimal("15"));
        assertEqualsDecimal("25", delivery.getLines().get(0).getInvoicedQty());
        assertEqualsDecimal("45", delivery.getLines().get(0).outstandingInvoiceableQty());
    }

    @Test
    void 出库行回滚多于已开票量_拒绝_下溢守门() {
        SalesDelivery delivery = completedDelivery("40");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> delivery.reverseInvoiceLine(1, new BigDecimal("41")));
        assertEqualsDecimal("40", delivery.getLines().get(0).getInvoicedQty());
        assertTrue(ex.getMessage().contains("超过已开票量"), ex.getMessage());
    }

    @Test
    void 出库行回滚量为零或负_拒绝() {
        SalesDelivery delivery = completedDelivery("40");
        assertThrows(IllegalArgumentException.class,
                () -> delivery.reverseInvoiceLine(1, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> delivery.reverseInvoiceLine(1, new BigDecimal("-1")));
    }

    @Test
    void 非COMPLETED出库单不可回滚开票量() {
        SalesDeliveryLine line = SalesDeliveryLine.restore(1L, 1, 1, 100L, new BigDecimal("70"),
                new BigDecimal("700.00"), new BigDecimal("40"));
        SalesDelivery draft = SalesDelivery.restore("SD-1", "SO-1", 1L, null,
                DocumentStatus.DRAFT, List.of(line), OPERATOR);
        assertThrows(IllegalStateException.class,
                () -> draft.reverseInvoiceLine(1, new BigDecimal("10")));
    }

    // ---------------------------------------------------------------
    // 销售订单行发货量回滚（SalesOrderLine.subtractDelivered，public）
    // ---------------------------------------------------------------

    private static SalesOrderLine orderLine(String deliveredQty) {
        return SalesOrderLine.restore(1L, 1, 100L, new BigDecimal("100"), new BigDecimal("20"),
                new BigDecimal("2000.00"), new BigDecimal(deliveredQty));
    }

    @Test
    void 订单发货量正常回滚_剩余可发恢复() {
        SalesOrderLine line = orderLine("70");
        line.subtractDelivered(new BigDecimal("70"));
        assertEqualsDecimal("0", line.getDeliveredQty());
        assertEqualsDecimal("100", line.remainingQty());
    }

    @Test
    void 订单发货量部分回滚_累计递减() {
        SalesOrderLine line = orderLine("70");
        line.subtractDelivered(new BigDecimal("30"));
        assertEqualsDecimal("40", line.getDeliveredQty());
        assertEqualsDecimal("60", line.remainingQty());
    }

    @Test
    void 订单发货量回滚多于已发量_拒绝_下溢守门() {
        SalesOrderLine line = orderLine("70");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> line.subtractDelivered(new BigDecimal("71")));
        assertEqualsDecimal("70", line.getDeliveredQty());
        assertTrue(ex.getMessage().contains("超过累计发货量"), ex.getMessage());
    }

    @Test
    void 订单发货量回滚为零或负_拒绝() {
        SalesOrderLine line = orderLine("70");
        assertThrows(IllegalArgumentException.class, () -> line.subtractDelivered(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> line.subtractDelivered(new BigDecimal("-1")));
        assertThrows(NullPointerException.class, () -> line.subtractDelivered(null));
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }
}
