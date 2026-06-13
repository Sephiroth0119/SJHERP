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
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 采购入库单领域服务单测（M3-T06）：引用采购订单、部分收货校验、过账构造（PURCHASE_IN + 回写
 * received_qty）、收货单价默认取采购订单行单价/可改、状态机、批量原子。
 * 用内存替身仓储 + 真实 PurchaseOrderService（验证到货量回写）+ 捕获库存端口。
 */
class PurchaseReceiptServiceTest {

    private static final long SUPPLIER = 1L;
    private static final long WAREHOUSE = 10L;
    private static final long P_A = 100L;
    private static final LocalDate D = LocalDate.of(2026, 6, 13);
    private static final String OPERATOR = "tester";

    private FakePurchaseOrderRepository orderRepo;
    private PurchaseOrderService orderService;
    private FakePurchaseReceiptRepository receiptRepo;
    private CapturingInventoryPort inventory;
    private PurchaseReceiptService service;

    @BeforeEach
    void setUp() {
        orderRepo = new FakePurchaseOrderRepository();
        orderService = new PurchaseOrderService(orderRepo, NoopPublisher.INSTANCE);
        receiptRepo = new FakePurchaseReceiptRepository();
        inventory = new CapturingInventoryPort();
        service = new PurchaseReceiptService(receiptRepo, orderService, inventory, NoopPublisher.INSTANCE);
    }

    /** 建一张已审核的采购订单（100 个 @12.5）供收货引用 */
    private void approvedOrder() {
        orderService.create("PO-1", SUPPLIER, D, null, List.of(
                new PurchaseOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("12.5"))), OPERATOR);
        orderService.approve("PO-1", OPERATOR);
    }

    // ----------------------------------------------------- 建单 + 引用校验

    @Test
    void 建单为草稿_收货单价默认取采购订单行单价() {
        approvedOrder();
        PurchaseReceipt receipt = service.create("PR-1", "PO-1", WAREHOUSE, D, "首批到货",
                List.of(receiptLine(1, "60", null)), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, receipt.getStatus());
        assertEquals("PO-1", receipt.getPurchaseOrderNo());
        assertEquals(1, receipt.getLines().size());
        assertEquals(P_A, receipt.getLines().get(0).getProductId());
        // 未传单价 → 取采购订单行单价 12.5
        assertEqualsDecimal("12.5", receipt.getLines().get(0).getUnitCost());
        // 入库金额 60 × 12.5 = 750.00
        assertEqualsDecimal("750.00", receipt.getLines().get(0).getAmount());
    }

    @Test
    void 收货单价可改() {
        approvedOrder();
        PurchaseReceipt receipt = service.create("PR-1", "PO-1", WAREHOUSE, D, null,
                List.of(receiptLine(1, "60", "13.0")), OPERATOR);
        assertEqualsDecimal("13.0", receipt.getLines().get(0).getUnitCost());
        assertEqualsDecimal("780.00", receipt.getLines().get(0).getAmount());
    }

    @Test
    void 引用未审核订单拒绝() {
        orderService.create("PO-1", SUPPLIER, D, null, List.of(
                new PurchaseOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("12.5"))), OPERATOR);
        // 草稿订单不可收货
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PR-1", "PO-1", WAREHOUSE, D, null,
                        List.of(receiptLine(1, "60", null)), OPERATOR));
        assertTrue(ex.getMessage().contains("不可收货"), ex.getMessage());
    }

    @Test
    void 引用不存在订单抛NotFound() {
        assertThrows(PurchaseOrderNotFoundException.class,
                () -> service.create("PR-1", "PO-NONE", WAREHOUSE, D, null,
                        List.of(receiptLine(1, "60", null)), OPERATOR));
    }

    @Test
    void 部分收货超未收量拒绝() {
        approvedOrder();
        // 订 100，一次收 120 超量 → 拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PR-1", "PO-1", WAREHOUSE, D, null,
                        List.of(receiptLine(1, "120", null)), OPERATOR));
        assertTrue(ex.getMessage().contains("超过采购订单行"), ex.getMessage());
    }

    @Test
    void 同收货单多行引用同订单行_累计校验超量() {
        approvedOrder();
        // 同单两行都引用 PO 行 1：60 + 50 = 110 > 100 → 拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PR-1", "PO-1", WAREHOUSE, D, null,
                        List.of(receiptLine(1, "60", null), receiptLine(1, "50", null)), OPERATOR));
        assertTrue(ex.getMessage().contains("超过采购订单行"), ex.getMessage());
    }

    @Test
    void 引用不存在的采购订单行号拒绝() {
        approvedOrder();
        assertThrows(IllegalArgumentException.class,
                () -> service.create("PR-1", "PO-1", WAREHOUSE, D, null,
                        List.of(receiptLine(9, "10", null)), OPERATOR));
    }

    // ----------------------------------------------------- 过账（PURCHASE_IN + 回写）

    @Test
    void 过账_构造PURCHASE_IN且回写到货量_一批原子() {
        approvedOrder();
        service.create("PR-1", "PO-1", WAREHOUSE, D, null, List.of(receiptLine(1, "60", null)), OPERATOR);
        service.approve("PR-1", OPERATOR);
        PurchaseReceipt posted = service.post("PR-1", OPERATOR);

        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
        // 一行 → 一笔入库，只 execute 一次
        assertEquals(1, inventory.executedBatches.size());
        List<StockMovementCommand> batch = inventory.lastBatch();
        assertEquals(1, batch.size());
        InboundCommand in = (InboundCommand) batch.get(0);
        assertEquals(InventoryTxnType.PURCHASE_IN, in.txnType());
        assertEquals(WAREHOUSE, in.warehouseId());
        assertEquals(P_A, in.productId());
        assertEqualsDecimal("60", in.quantity());
        // 入库单价 = 收货单价（默认取订单价 12.5）
        assertEqualsDecimal("12.5", in.unitCost());
        assertEquals("PURCHASE_RECEIPT", in.srcDocType());
        assertEquals("PR-1", in.srcDocNo());
        assertEquals("PURCHASE_RECEIPT:PR-1:1", in.idempotencyKey());

        // 到货量回写到采购订单行
        PurchaseOrder order = orderService.get("PO-1");
        assertEqualsDecimal("60", order.getLines().get(0).getReceivedQty());
        assertEqualsDecimal("40", order.getLines().get(0).outstandingQty());
    }

    @Test
    void 两次部分收货过账_订单到货量累计收齐() {
        approvedOrder();
        // 第一批 60
        service.create("PR-1", "PO-1", WAREHOUSE, D, null, List.of(receiptLine(1, "60", null)), OPERATOR);
        service.approve("PR-1", OPERATOR);
        service.post("PR-1", OPERATOR);
        // 第二批 40
        service.create("PR-2", "PO-1", WAREHOUSE, D, null, List.of(receiptLine(1, "40", null)), OPERATOR);
        service.approve("PR-2", OPERATOR);
        service.post("PR-2", OPERATOR);

        PurchaseOrder order = orderService.get("PO-1");
        assertEqualsDecimal("100", order.getLines().get(0).getReceivedQty());
        assertEqualsDecimal("0", order.getLines().get(0).outstandingQty());
        // 两批各一次 execute
        assertEquals(2, inventory.executedBatches.size());
    }

    @Test
    void 过账库存失败整体冒泡_到货量不应回写() {
        approvedOrder();
        service.create("PR-1", "PO-1", WAREHOUSE, D, null, List.of(receiptLine(1, "60", null)), OPERATOR);
        service.approve("PR-1", OPERATOR);
        inventory.failOnExecute = true;
        assertThrows(IllegalStateException.class, () -> service.post("PR-1", OPERATOR));
        // 库存过账先于回写，库存抛异常时回写未执行（真实环境靠外层事务整体回滚）
        PurchaseOrder order = orderService.get("PO-1");
        assertEqualsDecimal("0", order.getLines().get(0).getReceivedQty());
    }

    // ----------------------------------------------------- 状态机 / 冲销 / 查询

    @Test
    void 未审核直接过账非法流转() {
        approvedOrder();
        service.create("PR-1", "PO-1", WAREHOUSE, D, null, List.of(receiptLine(1, "10", null)), OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.post("PR-1", OPERATOR));
    }

    @Test
    void 冲销暂未实现_抛UnsupportedOperation() {
        approvedOrder();
        service.create("PR-1", "PO-1", WAREHOUSE, D, null, List.of(receiptLine(1, "10", null)), OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> service.reverse("PR-1", OPERATOR));
    }

    @Test
    void 查询不存在的入库单抛NotFound() {
        assertThrows(PurchaseReceiptNotFoundException.class, () -> service.get("PR-NONE"));
    }

    // ----------------------------------------------------- 工具

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static PurchaseReceiptLineInput receiptLine(int poLineNo, String quantity, String unitCost) {
        return new PurchaseReceiptLineInput(poLineNo, new BigDecimal(quantity),
                unitCost == null ? null : new BigDecimal(unitCost));
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

    /** 捕获过账批次的库存端口（可注入 execute 失败） */
    private static final class CapturingInventoryPort implements InventoryPostingPort {

        final List<List<StockMovementCommand>> executedBatches = new ArrayList<>();
        boolean failOnExecute;
        private final AtomicLong txnId = new AtomicLong();

        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            if (failOnExecute) {
                throw new IllegalStateException("模拟库存过账失败");
            }
            executedBatches.add(new ArrayList<>(batch));
            List<StockMovementResult> results = new ArrayList<>(batch.size());
            for (StockMovementCommand c : batch) {
                results.add(new StockMovementResult(txnId.incrementAndGet(), c.warehouseId(),
                        c.productId(), c.txnType(), BigDecimal.ZERO, null, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, c.srcDocType(), c.srcDocNo(),
                        c.srcLineNo(), c.idempotencyKey()));
            }
            return results;
        }

        List<StockMovementCommand> lastBatch() {
            return executedBatches.get(executedBatches.size() - 1);
        }
    }
}
