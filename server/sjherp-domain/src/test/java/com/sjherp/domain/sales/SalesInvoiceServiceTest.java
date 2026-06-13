package com.sjherp.domain.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 销售发票领域服务单测（M3-T10）：开票数量校验（不超出库已发量）、过账生成应收（OPEN）、金额计算、
 * 未过账出库单不可开票、状态机。用内存替身仓储 + 捕获应收端口；出库单链路用真实出库服务驱动。
 */
class SalesInvoiceServiceTest {

    private static final long WH = 1L;
    private static final long CUSTOMER = 7L;
    private static final long P_A = 100L;
    private static final String OPERATOR = "tester";
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 6, 13);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 14);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 7, 14);

    private SalesOrderService orderService;
    private SalesDeliveryService deliveryService;
    private FakeSalesInvoiceRepository invoiceRepository;
    private CapturingReceivablePort receivable;
    private SalesInvoiceService service;

    @BeforeEach
    void setUp() {
        orderService = new SalesOrderService(new FakeSalesOrderRepository(), NoopPublisher.INSTANCE);
        deliveryService = new SalesDeliveryService(new FakeSalesDeliveryRepository(), orderService,
                new CapturingInventoryPort(), NoopPublisher.INSTANCE);
        invoiceRepository = new FakeSalesInvoiceRepository();
        receivable = new CapturingReceivablePort();
        service = new SalesInvoiceService(invoiceRepository, deliveryService, receivable,
                NoopPublisher.INSTANCE);
    }

    /** 准备一张已过账出库单（行1 P_A 发 70；订单行1 P_A 100） */
    private void postedDelivery(String orderNo, String deliveryNo) {
        orderService.create(orderNo, CUSTOMER, ORDER_DATE, null,
                List.of(new SalesOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("20"))), OPERATOR);
        orderService.approve(orderNo, OPERATOR);
        deliveryService.create(deliveryNo, orderNo, WH, null,
                List.of(new SalesDeliveryLineInput(1, P_A, new BigDecimal("70"))), OPERATOR);
        deliveryService.approve(deliveryNo, OPERATOR);
        deliveryService.post(deliveryNo, OPERATOR);
    }

    @Test
    void 未过账出库单不能开票() {
        orderService.create("SO-1", CUSTOMER, ORDER_DATE, null,
                List.of(new SalesOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("20"))), OPERATOR);
        orderService.approve("SO-1", OPERATOR);
        deliveryService.create("SD-1", "SO-1", WH, null,
                List.of(new SalesDeliveryLineInput(1, P_A, new BigDecimal("70"))), OPERATOR);
        // 未过账（DRAFT）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                        List.of(invoiceLine(1, P_A, "70", "25")), OPERATOR));
        assertTrue(ex.getMessage().contains("未过账"), ex.getMessage());
    }

    @Test
    void 开票数量超过已发量拒绝() {
        postedDelivery("SO-1", "SD-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                        List.of(invoiceLine(1, P_A, "80", "25")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过已发货数量"), ex.getMessage());
    }

    @Test
    void 同单内对同一出库行累计超开拒绝() {
        postedDelivery("SO-1", "SD-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                        List.of(invoiceLine(1, P_A, "40", "25"), invoiceLine(1, P_A, "40", "25")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过已发货数量"), ex.getMessage());
    }

    @Test
    void 发票商品与出库行不一致拒绝() {
        postedDelivery("SO-1", "SD-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                        List.of(invoiceLine(1, 999L, "70", "25")), OPERATOR));
        assertTrue(ex.getMessage().contains("不一致"), ex.getMessage());
    }

    @Test
    void 建单成功_金额按数量乘单价() {
        postedDelivery("SO-1", "SD-1");
        SalesInvoice invoice = service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, "开票",
                List.of(invoiceLine(1, P_A, "70", "25")), OPERATOR);
        assertEquals(DocumentStatus.DRAFT, invoice.getStatus());
        // 70 × 25 = 1750.00
        assertEqualsDecimal("1750.00", invoice.getLines().get(0).getAmount());
        assertEqualsDecimal("1750.00", invoice.totalAmount());
    }

    @Test
    void 过账生成应收_OPEN_金额等于发票额() {
        postedDelivery("SO-1", "SD-1");
        service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                List.of(invoiceLine(1, P_A, "70", "25")), OPERATOR);
        service.approve("SINV-1", OPERATOR);
        SalesInvoice posted = service.post("SINV-1", OPERATOR);

        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
        assertEquals(1, receivable.opened.size());
        OpenedReceivable ar = receivable.opened.get(0);
        assertEquals(CUSTOMER, ar.customerId());
        assertEqualsDecimal("1750.00", ar.amount());
        assertEquals("SINV-1", ar.sourceDocNo());
        assertEquals(DUE_DATE, ar.dueDate());
    }

    @Test
    void 跨发票超额开票_发票1全额过账后发票2再开同出库行被拒() {
        postedDelivery("SO-1", "SD-1");
        // 发票1：对出库行 70 全额开票 → 审核 → 过账（回写出库行 invoicedQty=70）
        service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                List.of(invoiceLine(1, P_A, "70", "25")), OPERATOR);
        service.approve("SINV-1", OPERATOR);
        service.post("SINV-1", OPERATOR);
        assertEquals(1, receivable.opened.size());

        // 发票2：同出库行已无剩余可开票量（70 − 70 = 0），再开 1 → 被拒
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SINV-2", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                        List.of(invoiceLine(1, P_A, "1", "25")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过已发货数量"), ex.getMessage());
        // 第二笔应收未生成（虚增应收被挡住）
        assertEquals(1, receivable.opened.size());
    }

    @Test
    void 跨发票分次开票_发票1部分发票2开剩余OK再多开被拒() {
        postedDelivery("SO-1", "SD-1");
        // 发票1：开 40（剩余 30）→ 过账
        service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                List.of(invoiceLine(1, P_A, "40", "25")), OPERATOR);
        service.approve("SINV-1", OPERATOR);
        service.post("SINV-1", OPERATOR);

        // 发票2：开剩余 30 → OK（40 + 30 = 70 = 已发货量）
        SalesInvoice inv2 = service.create("SINV-2", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                List.of(invoiceLine(1, P_A, "30", "25")), OPERATOR);
        assertEqualsDecimal("30", inv2.getLines().get(0).getQuantity());
        service.approve("SINV-2", OPERATOR);
        service.post("SINV-2", OPERATOR);
        assertEquals(2, receivable.opened.size());

        // 发票3：已开满 70，再开 1 → 被拒
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SINV-3", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                        List.of(invoiceLine(1, P_A, "1", "25")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过已发货数量"), ex.getMessage());
        assertEquals(2, receivable.opened.size());
    }

    @Test
    void 过账回写出库行已开票量() {
        postedDelivery("SO-1", "SD-1");
        service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                List.of(invoiceLine(1, P_A, "40", "25")), OPERATOR);
        service.approve("SINV-1", OPERATOR);
        service.post("SINV-1", OPERATOR);
        // 出库行 invoicedQty 回写为 40、剩余可开票量 30
        SalesDeliveryLine line = deliveryService.get("SD-1").getLines().get(0);
        assertEqualsDecimal("40", line.getInvoicedQty());
        assertEqualsDecimal("30", line.outstandingInvoiceableQty());
    }

    @Test
    void 退货冲销暂未实现_抛UnsupportedOperation() {
        postedDelivery("SO-1", "SD-1");
        service.create("SINV-1", "SD-1", CUSTOMER, INVOICE_DATE, DUE_DATE, null,
                List.of(invoiceLine(1, P_A, "70", "25")), OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> service.reverse("SINV-1", OPERATOR));
    }

    @Test
    void 查询不存在的发票抛NotFound() {
        assertThrows(SalesInvoiceNotFoundException.class, () -> service.get("SINV-NONE"));
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static SalesInvoiceLineInput invoiceLine(int deliveryLineNo, long productId, String qty,
                                                     String unitPrice) {
        return new SalesInvoiceLineInput(deliveryLineNo, productId, new BigDecimal(qty),
                new BigDecimal(unitPrice));
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private enum NoopPublisher implements DomainEventPublisher {
        INSTANCE;

        @Override
        public void publish(DomainEvent event) {
            // no-op
        }
    }

    private record OpenedReceivable(long customerId, BigDecimal amount, String sourceDocNo,
                                    LocalDate dueDate) {
    }

    private static final class CapturingReceivablePort implements ReceivablePostingPort {

        final List<OpenedReceivable> opened = new ArrayList<>();

        @Override
        public void open(long customerId, BigDecimal amount, String sourceDocNo, LocalDate dueDate,
                         String operator) {
            opened.add(new OpenedReceivable(customerId, amount, sourceDocNo, dueDate));
        }
    }

    private static final class FakeSalesOrderRepository implements SalesOrderRepository {
        private final Map<String, SalesOrder> store = new HashMap<>();

        @Override
        public void save(SalesOrder order) {
            store.put(order.getDocNo(), order);
        }

        @Override
        public Optional<SalesOrder> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<SalesOrder> search(SalesOrderQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(), query.page(), query.size());
        }
    }

    private static final class FakeSalesDeliveryRepository implements SalesDeliveryRepository {
        private final Map<String, SalesDelivery> store = new HashMap<>();

        @Override
        public void save(SalesDelivery delivery) {
            store.put(delivery.getDocNo(), delivery);
        }

        @Override
        public Optional<SalesDelivery> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<SalesDelivery> search(SalesDeliveryQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(), query.page(), query.size());
        }
    }

    private static final class FakeSalesInvoiceRepository implements SalesInvoiceRepository {
        private final Map<String, SalesInvoice> store = new HashMap<>();

        @Override
        public void save(SalesInvoice invoice) {
            store.put(invoice.getDocNo(), invoice);
        }

        @Override
        public Optional<SalesInvoice> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<SalesInvoice> search(SalesInvoiceQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(), query.page(), query.size());
        }
    }

    /** 出库链路用的库存替身：默认出库 totalCost = -1（满足 COGS 回填非负） */
    private static final class CapturingInventoryPort implements InventoryPostingPort {
        private final AtomicLong txnId = new AtomicLong();

        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            List<StockMovementResult> results = new ArrayList<>(batch.size());
            for (StockMovementCommand c : batch) {
                OutboundCommand out = (OutboundCommand) c;
                assertEquals(InventoryTxnType.SALES_OUT, out.txnType());
                StockMovementResult r = new StockMovementResult(txnId.incrementAndGet(), c.warehouseId(),
                        c.productId(), c.txnType(), out.quantity().negate(), null, new BigDecimal("-1"),
                        BigDecimal.ZERO, BigDecimal.ZERO, c.srcDocType(), c.srcDocNo(),
                        c.srcLineNo(), c.idempotencyKey());
                assertNotNull(r);
                results.add(r);
            }
            return results;
        }
    }
}
