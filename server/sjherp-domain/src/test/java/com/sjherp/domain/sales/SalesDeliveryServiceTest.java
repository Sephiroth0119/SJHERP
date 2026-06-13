package com.sjherp.domain.sales;

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
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 销售出库单领域服务单测（M3-T09）：建单引用订单校验、部分发货剩余可发量校验、SALES_OUT 组批构造、
 * <b>COGS 回填出库行</b>（取库存结果 totalCost 负数转正）、库存不足整批回滚、回写订单累计发货量、
 * 状态机、退货冲销 TODO。用内存替身仓储 + 捕获库存端口。
 */
class SalesDeliveryServiceTest {

    private static final long WH = 1L;
    private static final long CUSTOMER = 7L;
    private static final long P_A = 100L;
    private static final long P_B = 200L;
    private static final String OPERATOR = "tester";
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 6, 13);

    private FakeSalesOrderRepository orderRepository;
    private SalesOrderService orderService;
    private FakeSalesDeliveryRepository deliveryRepository;
    private CapturingInventoryPort inventory;
    private SalesDeliveryService service;

    @BeforeEach
    void setUp() {
        orderRepository = new FakeSalesOrderRepository();
        orderService = new SalesOrderService(orderRepository, NoopPublisher.INSTANCE);
        deliveryRepository = new FakeSalesDeliveryRepository();
        inventory = new CapturingInventoryPort();
        service = new SalesDeliveryService(deliveryRepository, orderService, inventory,
                NoopPublisher.INSTANCE);
    }

    /** 准备一张已审核销售订单（行1 P_A 100、行2 P_B 50） */
    private void approvedOrder(String docNo) {
        orderService.create(docNo, CUSTOMER, ORDER_DATE, null,
                List.of(new SalesOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("20")),
                        new SalesOrderLineInput(P_B, new BigDecimal("50"), new BigDecimal("30"))), OPERATOR);
        orderService.approve(docNo, OPERATOR);
    }

    // ---------------------------------------------------------------
    // 建单校验
    // ---------------------------------------------------------------

    @Test
    void 草稿订单不能发货() {
        orderService.create("SO-1", CUSTOMER, ORDER_DATE, null,
                List.of(new SalesOrderLineInput(P_A, new BigDecimal("100"), new BigDecimal("20"))), OPERATOR);
        // 未审核
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SD-1", "SO-1", WH, null,
                        List.of(deliveryLine(1, P_A, "10")), OPERATOR));
        assertTrue(ex.getMessage().contains("不可发货"), ex.getMessage());
    }

    @Test
    void 引用不存在的订单拒绝() {
        assertThrows(SalesOrderNotFoundException.class,
                () -> service.create("SD-1", "SO-NONE", WH, null,
                        List.of(deliveryLine(1, P_A, "10")), OPERATOR));
    }

    @Test
    void 出库行商品与订单行不一致拒绝() {
        approvedOrder("SO-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SD-1", "SO-1", WH, null,
                        List.of(deliveryLine(1, P_B, "10")), OPERATOR));
        assertTrue(ex.getMessage().contains("不一致"), ex.getMessage());
    }

    @Test
    void 发货数量超过剩余可发量拒绝() {
        approvedOrder("SO-1");
        // 行1 订单 100，发 120 超发
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SD-1", "SO-1", WH, null,
                        List.of(deliveryLine(1, P_A, "120")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过剩余可发量"), ex.getMessage());
    }

    @Test
    void 同单内对同一订单行累计超发拒绝() {
        approvedOrder("SO-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("SD-1", "SO-1", WH, null,
                        List.of(deliveryLine(1, P_A, "60"), deliveryLine(1, P_A, "60")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过剩余可发量"), ex.getMessage());
    }

    @Test
    void 部分发货建单成功() {
        approvedOrder("SO-1");
        SalesDelivery delivery = service.create("SD-1", "SO-1", WH, "首批",
                List.of(deliveryLine(1, P_A, "70")), OPERATOR);
        assertEquals(DocumentStatus.DRAFT, delivery.getStatus());
        assertEquals(1, delivery.getLines().size());
        assertEqualsDecimal("70", delivery.getLines().get(0).getQuantity());
    }

    // ---------------------------------------------------------------
    // 过账：SALES_OUT 组批 + COGS 回填 + 回写订单累计发货量
    // ---------------------------------------------------------------

    @Test
    void 过账每行SALES_OUT组一批_幂等键约定() {
        approvedOrder("SO-1");
        service.create("SD-1", "SO-1", WH, null, List.of(deliveryLine(1, P_A, "70")), OPERATOR);
        service.approve("SD-1", OPERATOR);
        service.post("SD-1", OPERATOR);

        assertEquals(1, inventory.executedBatches.size());
        List<StockMovementCommand> batch = inventory.lastBatch();
        assertEquals(1, batch.size());
        OutboundCommand out = (OutboundCommand) batch.get(0);
        assertEquals(InventoryTxnType.SALES_OUT, out.txnType());
        assertEquals(WH, out.warehouseId());
        assertEquals(P_A, out.productId());
        assertEqualsDecimal("70", out.quantity());
        assertEquals("SALES_DELIVERY", out.srcDocType());
        assertEquals("SD-1", out.srcDocNo());
        assertEquals(1, out.srcLineNo());
        assertEquals("SALES_DELIVERY:SD-1:1", out.idempotencyKey());
    }

    @Test
    void 过账后COGS记到出库行_取库存结果totalCost负数转正() {
        approvedOrder("SO-1");
        service.create("SD-1", "SO-1", WH, null, List.of(deliveryLine(1, P_A, "70")), OPERATOR);
        service.approve("SD-1", OPERATOR);
        // 库存端口返回出库 totalCost = -762.61（移动加权口径，出库为负）
        inventory.cogsTotalByKey.put("SALES_DELIVERY:SD-1:1", new BigDecimal("-762.61"));

        SalesDelivery posted = service.post("SD-1", OPERATOR);
        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
        // COGS 回填为正数 762.61
        assertEqualsDecimal("762.61", posted.getLines().get(0).getCogsAmount());
        assertEqualsDecimal("762.61", posted.totalCogs());
    }

    @Test
    void 过账回写订单累计发货量() {
        approvedOrder("SO-1");
        service.create("SD-1", "SO-1", WH, null,
                List.of(deliveryLine(1, P_A, "70"), deliveryLine(2, P_B, "20")), OPERATOR);
        service.approve("SD-1", OPERATOR);
        service.post("SD-1", OPERATOR);

        SalesOrder order = orderService.get("SO-1");
        assertEqualsDecimal("70", order.lineByNo(1).getDeliveredQty());
        assertEqualsDecimal("30", order.lineByNo(1).remainingQty());
        assertEqualsDecimal("20", order.lineByNo(2).getDeliveredQty());
    }

    @Test
    void 库存不足整批回滚_异常冒泡() {
        approvedOrder("SO-1");
        service.create("SD-1", "SO-1", WH, null, List.of(deliveryLine(1, P_A, "70")), OPERATOR);
        service.approve("SD-1", OPERATOR);
        inventory.throwInsufficientStock = true;

        assertThrows(InsufficientStockException.class, () -> service.post("SD-1", OPERATOR));
        // 库存抛异常时领域服务不吞——由外层事务回滚（这里替身未执行任何过账）
        assertEquals(0, inventory.executedBatches.size());
    }

    @Test
    void 未审核直接过账非法流转() {
        approvedOrder("SO-1");
        service.create("SD-1", "SO-1", WH, null, List.of(deliveryLine(1, P_A, "70")), OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.post("SD-1", OPERATOR));
    }

    @Test
    void 已完成出库单再过账非法流转() {
        approvedOrder("SO-1");
        service.create("SD-1", "SO-1", WH, null, List.of(deliveryLine(1, P_A, "70")), OPERATOR);
        service.approve("SD-1", OPERATOR);
        service.post("SD-1", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.post("SD-1", OPERATOR));
    }

    @Test
    void 退货冲销暂未实现_抛UnsupportedOperation() {
        approvedOrder("SO-1");
        service.create("SD-1", "SO-1", WH, null, List.of(deliveryLine(1, P_A, "1")), OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> service.reverse("SD-1", OPERATOR));
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static SalesDeliveryLineInput deliveryLine(int soLineNo, long productId, String qty) {
        return new SalesDeliveryLineInput(soLineNo, productId, new BigDecimal(qty));
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
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
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
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }

    /** 捕获过账批次的库存端口：可注入库存不足、按幂等键指定出库 totalCost（默认 -1 占位） */
    private static final class CapturingInventoryPort implements InventoryPostingPort {

        final List<List<StockMovementCommand>> executedBatches = new ArrayList<>();
        final Map<String, BigDecimal> cogsTotalByKey = new HashMap<>();
        boolean throwInsufficientStock;
        private final AtomicLong txnId = new AtomicLong();

        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            if (throwInsufficientStock) {
                OutboundCommand first = (OutboundCommand) batch.get(0);
                throw new InsufficientStockException(first.warehouseId(), first.productId(),
                        BigDecimal.ZERO, first.quantity());
            }
            executedBatches.add(new ArrayList<>(batch));
            List<StockMovementResult> results = new ArrayList<>(batch.size());
            for (StockMovementCommand c : batch) {
                // 销售出库链路一律 OutboundCommand（SALES_OUT），由此取出库数量
                OutboundCommand out = (OutboundCommand) c;
                // 默认出库 totalCost = -1（负数口径），可被 cogsTotalByKey 覆盖
                BigDecimal total = cogsTotalByKey.getOrDefault(c.idempotencyKey(), new BigDecimal("-1"));
                results.add(new StockMovementResult(txnId.incrementAndGet(), c.warehouseId(),
                        c.productId(), c.txnType(), out.quantity().negate(), null, total,
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
