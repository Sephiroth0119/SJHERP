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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 采购订单领域服务单测（M3-T05）：状态机全路径、行金额计算、关闭、收货回写与超量校验、
 * BigDecimal 边界。用内存替身仓储，不依赖 Spring/DB。
 */
class PurchaseOrderServiceTest {

    private static final long SUPPLIER = 1L;
    private static final long P_A = 100L;
    private static final long P_B = 200L;
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 6, 13);
    private static final String OPERATOR = "tester";

    private FakePurchaseOrderRepository repository;
    private PurchaseOrderService service;

    @BeforeEach
    void setUp() {
        repository = new FakePurchaseOrderRepository();
        service = new PurchaseOrderService(repository, NoopPublisher.INSTANCE);
    }

    // ----------------------------------------------------- 建单

    @Test
    void 建单为草稿_行号自增_金额自动计算() {
        PurchaseOrder order = service.create("PO-202606-0001", SUPPLIER, ORDER_DATE, "常规采购",
                List.of(line(P_A, "100", "12.5"), line(P_B, "30", "11.2")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, order.getStatus());
        assertEquals(SUPPLIER, order.getSupplierId());
        assertEquals(2, order.getLines().size());
        assertEquals(1, order.getLines().get(0).getLineNo());
        // 100 × 12.5 = 1250.00；30 × 11.2 = 336.00
        assertEqualsDecimal("1250.00", order.getLines().get(0).getAmount());
        assertEqualsDecimal("336.00", order.getLines().get(1).getAmount());
        assertEqualsDecimal("1586.00", order.totalAmount());
        // 到货量初始 0、未到货量 = 订购量
        assertEqualsDecimal("0", order.getLines().get(0).getReceivedQty());
        assertEqualsDecimal("100", order.getLines().get(0).outstandingQty());
        assertTrue(repository.findByDocNo("PO-202606-0001").isPresent());
    }

    @Test
    void 行金额舍入_HALF_UP_两位() {
        // 3 × 0.335 = 1.005 → 1.01（HALF_UP）
        PurchaseOrder order = service.create("PO-1", SUPPLIER, ORDER_DATE, null,
                List.of(line(P_A, "3", "0.335")), OPERATOR);
        assertEqualsDecimal("1.01", order.getLines().get(0).getAmount());
    }

    @Test
    void 建单空行拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(), OPERATOR));
    }

    @Test
    void 数量为零或负拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "0", "1")), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("PO-2", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "-1", "1")), OPERATOR));
    }

    @Test
    void 单价为负拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "1", "-1")), OPERATOR));
    }

    @Test
    void 单价为零放行() {
        PurchaseOrder order = service.create("PO-1", SUPPLIER, ORDER_DATE, null,
                List.of(line(P_A, "5", "0")), OPERATOR);
        assertEqualsDecimal("0.00", order.getLines().get(0).getAmount());
    }

    @Test
    void operator为空拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "1", "1")), " "));
    }

    // ----------------------------------------------------- 状态机

    @Test
    void 状态机全路径_草稿到审核到关闭() {
        service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "100", "10")), OPERATOR);
        assertEquals(DocumentStatus.APPROVED, service.approve("PO-1", OPERATOR).getStatus());
        assertEquals(DocumentStatus.COMPLETED, service.close("PO-1", OPERATOR).getStatus());
    }

    @Test
    void 未审核直接关闭非法流转() {
        service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "1", "1")), OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.close("PO-1", OPERATOR));
    }

    @Test
    void 重复审核非法流转() {
        service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "1", "1")), OPERATOR);
        service.approve("PO-1", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.approve("PO-1", OPERATOR));
    }

    // ----------------------------------------------------- 收货回写（部分收货）

    @Test
    void 收货回写到货量_部分收货累计() {
        service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "100", "10")), OPERATOR);
        service.approve("PO-1", OPERATOR);

        // 第一次收 60
        service.applyReceipt("PO-1", List.of(received(1, "60")), OPERATOR);
        PurchaseOrder afterFirst = service.get("PO-1");
        assertEqualsDecimal("60", afterFirst.getLines().get(0).getReceivedQty());
        assertEqualsDecimal("40", afterFirst.getLines().get(0).outstandingQty());

        // 第二次收 40，收齐
        service.applyReceipt("PO-1", List.of(received(1, "40")), OPERATOR);
        PurchaseOrder afterSecond = service.get("PO-1");
        assertEqualsDecimal("100", afterSecond.getLines().get(0).getReceivedQty());
        assertEqualsDecimal("0", afterSecond.getLines().get(0).outstandingQty());
    }

    @Test
    void 收货超量拒绝() {
        service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "100", "10")), OPERATOR);
        service.approve("PO-1", OPERATOR);
        service.applyReceipt("PO-1", List.of(received(1, "60")), OPERATOR);
        // 已收 60，再收 50 超过未收量 40 → 拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.applyReceipt("PO-1", List.of(received(1, "50")), OPERATOR));
        assertTrue(ex.getMessage().contains("超过订购数量"), ex.getMessage());
    }

    @Test
    void 未审核订单收货拒绝() {
        service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "100", "10")), OPERATOR);
        // 草稿状态收货被拒（仅 APPROVED 可收货）
        assertThrows(IllegalStateException.class,
                () -> service.applyReceipt("PO-1", List.of(received(1, "10")), OPERATOR));
    }

    @Test
    void 收货引用不存在行号拒绝() {
        service.create("PO-1", SUPPLIER, ORDER_DATE, null, List.of(line(P_A, "100", "10")), OPERATOR);
        service.approve("PO-1", OPERATOR);
        assertThrows(IllegalArgumentException.class,
                () -> service.applyReceipt("PO-1", List.of(received(9, "10")), OPERATOR));
    }

    // ----------------------------------------------------- 查询

    @Test
    void 查询不存在的采购订单抛NotFound() {
        assertThrows(PurchaseOrderNotFoundException.class, () -> service.get("PO-NONE"));
    }

    // ----------------------------------------------------- 工具

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static PurchaseOrderLineInput line(long productId, String quantity, String unitPrice) {
        return new PurchaseOrderLineInput(productId, new BigDecimal(quantity), new BigDecimal(unitPrice));
    }

    private static PurchaseOrderService.ReceivedLine received(int lineNo, String quantity) {
        return new PurchaseOrderService.ReceivedLine(lineNo, new BigDecimal(quantity));
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
}
