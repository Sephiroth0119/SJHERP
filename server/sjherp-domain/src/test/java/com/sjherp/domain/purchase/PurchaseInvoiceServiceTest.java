package com.sjherp.domain.purchase;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.PayableStatus;

/**
 * 采购发票领域服务单测（M3-T07）：三单匹配（开票数量超已收拒绝）、过账生成应付、到期日按结算方式
 * 推算、过账幂等（同发票不重复生成应付）、状态机。用内存替身 + 真实 PO/收货服务（构造已过账收货单）
 * + 捕获应付端口。
 */
class PurchaseInvoiceServiceTest {

    private static final long SUPPLIER = 1L;
    private static final long WAREHOUSE = 10L;
    private static final long P_A = 100L;
    private static final LocalDate D = LocalDate.of(2026, 6, 13);
    private static final String OPERATOR = "tester";

    private PurchaseOrderService orderService;
    private PurchaseReceiptService receiptService;
    private FakePurchaseInvoiceRepository invoiceRepo;
    private CapturingPayablePort payable;
    private PurchaseInvoiceService service;

    @BeforeEach
    void setUp() {
        FakePurchaseOrderRepository orderRepo = new FakePurchaseOrderRepository();
        orderService = new PurchaseOrderService(orderRepo, NoopPublisher.INSTANCE);
        FakePurchaseReceiptRepository receiptRepo = new FakePurchaseReceiptRepository();
        receiptService = new PurchaseReceiptService(receiptRepo, orderService,
                new NoopInventoryPort(), NoopPublisher.INSTANCE);
        invoiceRepo = new FakePurchaseInvoiceRepository();
        payable = new CapturingPayablePort();
        service = new PurchaseInvoiceService(invoiceRepo, receiptService, payable, NoopPublisher.INSTANCE);
    }

    /** 建一张已过账的采购入库单（收 60 @12.5）供开票引用，返回其单号 */
    private String completedReceipt() {
        orderService.create("PO-1", SUPPLIER, D, null, List.of(
                new PurchaseOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("12.5"))), OPERATOR);
        orderService.approve("PO-1", OPERATOR);
        receiptService.create("PR-1", "PO-1", WAREHOUSE, D, null,
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("60"), null)), OPERATOR);
        receiptService.approve("PR-1", OPERATOR);
        receiptService.post("PR-1", OPERATOR);
        return "PR-1";
    }

    // ----------------------------------------------------- 建单 + 三单匹配

    @Test
    void 建单为草稿_引用收货行_金额由开票方给出() {
        completedReceipt();
        PurchaseInvoice invoice = service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY,
                D, "INV-A001", null, List.of(invoiceLine(1, "60", "750.00")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, invoice.getStatus());
        assertEquals("PR-1", invoice.getPurchaseReceiptNo());
        assertEquals(1, invoice.getLines().size());
        assertEqualsDecimal("60", invoice.getLines().get(0).getQuantity());
        assertEqualsDecimal("750.00", invoice.totalAmount());
    }

    @Test
    void 金额含运费可与货款不同() {
        completedReceipt();
        // 货款 750，发票金额 800（含运费 50），金额不强制等于数量乘单价 → 放行
        PurchaseInvoice invoice = service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY,
                D, null, null, List.of(invoiceLine(1, "60", "800.00")), OPERATOR);
        assertEqualsDecimal("800.00", invoice.totalAmount());
    }

    @Test
    void 三单匹配_开票数量超已收拒绝() {
        completedReceipt();
        // 已收 60，开票 70 > 60 → 拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY,
                        D, null, null, List.of(invoiceLine(1, "70", "875.00")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过采购入库单行"), ex.getMessage());
    }

    @Test
    void 三单匹配_同发票多行引用同收货行累计超量拒绝() {
        completedReceipt();
        // 同发票两行引用收货行 1：40 + 30 = 70 > 60 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY,
                        D, null, null, List.of(invoiceLine(1, "40", "500"), invoiceLine(1, "30", "375")),
                        OPERATOR));
    }

    @Test
    void 跨发票超额开票_发票1全额过账后发票2再开同收货行被拒() {
        completedReceipt();
        // 发票1：开满 60 → 审核 → 过账（回写收货行 invoicedQty=60）
        service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                List.of(invoiceLine(1, "60", "750")), OPERATOR);
        service.approve("PINV-1", OPERATOR);
        service.post("PINV-1", SettlementMethod.MONTHLY, OPERATOR);
        assertEquals(1, payable.saved.size());

        // 发票2：同收货行已无剩余可开票量（60 − 60 = 0），再开 1 → 被拒
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PINV-2", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                        List.of(invoiceLine(1, "1", "12.5")), OPERATOR));
        assertTrue(ex.getMessage().contains("剩余可开票量"), ex.getMessage());
        // 第二笔应付未生成（虚增应付被挡住）
        assertEquals(1, payable.saved.size());
    }

    @Test
    void 跨发票分次开票_发票1部分发票2开剩余OK再多开被拒() {
        completedReceipt();
        // 发票1：开 40（剩余 20）→ 过账
        service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                List.of(invoiceLine(1, "40", "500")), OPERATOR);
        service.approve("PINV-1", OPERATOR);
        service.post("PINV-1", SettlementMethod.MONTHLY, OPERATOR);

        // 发票2：开剩余 20 → OK（40 + 20 = 60 = 收货量）
        PurchaseInvoice inv2 = service.create("PINV-2", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D,
                null, null, List.of(invoiceLine(1, "20", "250")), OPERATOR);
        assertEqualsDecimal("20", inv2.getLines().get(0).getQuantity());
        service.approve("PINV-2", OPERATOR);
        service.post("PINV-2", SettlementMethod.MONTHLY, OPERATOR);
        assertEquals(2, payable.saved.size());

        // 发票3：已开满 60，再开 1 → 被拒
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PINV-3", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                        List.of(invoiceLine(1, "1", "12.5")), OPERATOR));
        assertTrue(ex.getMessage().contains("剩余可开票量"), ex.getMessage());
        assertEquals(2, payable.saved.size());
    }

    @Test
    void 过账回写收货行已开票量() {
        completedReceipt();
        service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                List.of(invoiceLine(1, "40", "500")), OPERATOR);
        service.approve("PINV-1", OPERATOR);
        service.post("PINV-1", SettlementMethod.MONTHLY, OPERATOR);
        // 收货行 invoicedQty 回写为 40、剩余可开票量 20
        PurchaseReceiptLine line = receiptService.get("PR-1").getLines().get(0);
        assertEqualsDecimal("40", line.getInvoicedQty());
        assertEqualsDecimal("20", line.outstandingInvoiceableQty());
    }

    @Test
    void 引用未过账收货单拒绝() {
        orderService.create("PO-1", SUPPLIER, D, null, List.of(
                new PurchaseOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("12.5"))), OPERATOR);
        orderService.approve("PO-1", OPERATOR);
        receiptService.create("PR-1", "PO-1", WAREHOUSE, D, null,
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("60"), null)), OPERATOR);
        // 收货单仅草稿（未过账）→ 不可开票
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY,
                        D, null, null, List.of(invoiceLine(1, "60", "750")), OPERATOR));
        assertTrue(ex.getMessage().contains("未过账"), ex.getMessage());
    }

    // ----------------------------------------------------- 过账生成应付

    @Test
    void 过账生成应付_金额等于发票总额_状态OPEN() {
        completedReceipt();
        service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                List.of(invoiceLine(1, "60", "800.00")), OPERATOR);
        service.approve("PINV-1", OPERATOR);
        PurchaseInvoice posted = service.post("PINV-1", SettlementMethod.MONTHLY, OPERATOR);

        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
        assertEquals(1, payable.saved.size());
        AccountsPayable ap = payable.saved.get(0);
        assertEquals(SUPPLIER, ap.getSupplierId());
        assertEqualsDecimal("800.00", ap.getAmount());
        assertEquals("PINV-1", ap.getSourceDocNo());
        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
        // 月结到期日 = 发票日 + 1 个月
        assertEquals(D.plusMonths(1), ap.getDueDate());
    }

    @Test
    void 到期日_现结与预付为发票日() {
        completedReceipt();
        service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.CASH, D, null, null,
                List.of(invoiceLine(1, "60", "750")), OPERATOR);
        service.approve("PINV-1", OPERATOR);
        service.post("PINV-1", SettlementMethod.CASH, OPERATOR);
        assertEquals(D, payable.saved.get(0).getDueDate());
    }

    @Test
    void 过账幂等_重复过账不重复生成应付() {
        completedReceipt();
        service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                List.of(invoiceLine(1, "60", "750")), OPERATOR);
        service.approve("PINV-1", OPERATOR);
        service.post("PINV-1", SettlementMethod.MONTHLY, OPERATOR);
        // 已 COMPLETED，再过账会非法流转（但幂等口径也保证不会二次生成应付）
        assertThrows(RuntimeException.class, () -> service.post("PINV-1", SettlementMethod.MONTHLY, OPERATOR));
        assertEquals(1, payable.saved.size());
    }

    @Test
    void 冲销暂未实现_抛UnsupportedOperation() {
        completedReceipt();
        service.create("PINV-1", "PR-1", SUPPLIER, SettlementMethod.MONTHLY, D, null, null,
                List.of(invoiceLine(1, "60", "750")), OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> service.reverse("PINV-1", OPERATOR));
    }

    @Test
    void 查询不存在的发票抛NotFound() {
        assertThrows(PurchaseInvoiceNotFoundException.class, () -> service.get("PINV-NONE"));
    }

    // ----------------------------------------------------- 工具

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static PurchaseInvoiceLineInput invoiceLine(int receiptLineNo, String quantity, String amount) {
        return new PurchaseInvoiceLineInput(receiptLineNo, new BigDecimal(quantity), new BigDecimal(amount));
    }

    private enum NoopPublisher implements DomainEventPublisher {
        INSTANCE;

        @Override
        public void publish(DomainEvent event) {
            // no-op
        }
    }

    private static final class FakePurchaseOrderRepository implements PurchaseOrderRepository {

        private final Map<String, PurchaseOrder> store = new HashMap<>();

        @Override
        public void save(PurchaseOrder order) {
            store.put(order.getDocNo(), order);
        }

        @Override
        public Optional<PurchaseOrder> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<PurchaseOrder> search(PurchaseOrderQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }

    private static final class FakePurchaseReceiptRepository implements PurchaseReceiptRepository {

        private final Map<String, PurchaseReceipt> store = new HashMap<>();

        @Override
        public void save(PurchaseReceipt receipt) {
            store.put(receipt.getDocNo(), receipt);
        }

        @Override
        public Optional<PurchaseReceipt> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<PurchaseReceipt> search(PurchaseReceiptQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }

    private static final class FakePurchaseInvoiceRepository implements PurchaseInvoiceRepository {

        private final Map<String, PurchaseInvoice> store = new HashMap<>();

        @Override
        public void save(PurchaseInvoice invoice) {
            store.put(invoice.getDocNo(), invoice);
        }

        @Override
        public Optional<PurchaseInvoice> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<PurchaseInvoice> search(PurchaseInvoiceQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }

    /** 无操作库存端口：本测试不验证库存流水，仅需收货单能过账到 COMPLETED 供开票引用 */
    private static final class NoopInventoryPort implements InventoryPostingPort {

        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            return List.of();
        }
    }

    /** 捕获应付生成的端口（带按来源单据号查重支持幂等断言） */
    private static final class CapturingPayablePort implements AccountsPayablePort {

        final List<AccountsPayable> saved = new ArrayList<>();
        private final AtomicLong idSeq = new AtomicLong();

        @Override
        public void save(AccountsPayable payableRecord) {
            payableRecord.assignId(idSeq.incrementAndGet());
            saved.add(payableRecord);
        }

        @Override
        public List<AccountsPayable> findBySourceDocNo(String sourceDocNo) {
            return saved.stream().filter(p -> p.getSourceDocNo().equals(sourceDocNo)).toList();
        }
    }
}
