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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 销售订单领域服务单测（M3-T08）：建单/金额计算、状态机全路径、行号自增、累计发货量回写与超发拒绝、
 * BigDecimal 边界。用内存替身仓储，不依赖 Spring/DB。
 */
class SalesOrderServiceTest {

    private static final long CUSTOMER = 7L;
    private static final long P_A = 100L;
    private static final long P_B = 200L;
    private static final String OPERATOR = "tester";
    private static final LocalDate ORDER_DATE = LocalDate.of(2026, 6, 13);

    private FakeSalesOrderRepository repository;
    private SalesOrderService service;

    @BeforeEach
    void setUp() {
        repository = new FakeSalesOrderRepository();
        service = new SalesOrderService(repository, NoopPublisher.INSTANCE);
    }

    @Test
    void 建单为草稿_行号自增_金额按数量乘单价() {
        SalesOrder order = service.create("SO-202606-0001", CUSTOMER, ORDER_DATE, "首单",
                List.of(line(P_A, "100", "12.50"), line(P_B, "3", "11.20")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, order.getStatus());
        assertEquals(CUSTOMER, order.getCustomerId());
        assertEquals(2, order.getLines().size());
        assertEquals(1, order.getLines().get(0).getLineNo());
        assertEquals(2, order.getLines().get(1).getLineNo());
        // 行金额 100×12.50=1250.00、3×11.20=33.60
        assertEqualsDecimal("1250.00", order.getLines().get(0).getAmount());
        assertEqualsDecimal("33.60", order.getLines().get(1).getAmount());
        // 总额 1283.60
        assertEqualsDecimal("1283.60", order.totalAmount());
        // 累计发货量初始 0、剩余可发 = 数量
        assertEqualsDecimal("0", order.getLines().get(0).getDeliveredQty());
        assertEqualsDecimal("100", order.getLines().get(0).remainingQty());
    }

    @Test
    void 金额四舍五入_HALF_UP() {
        // 3 × 3.335 = 10.005 → 10.01（HALF_UP）
        SalesOrder order = service.create("SO-1", CUSTOMER, ORDER_DATE, null,
                List.of(line(P_A, "3", "3.335")), OPERATOR);
        assertEqualsDecimal("10.01", order.getLines().get(0).getAmount());
    }

    @Test
    void 建单空行拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(), OPERATOR));
    }

    @Test
    void 数量为零或负拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "0", "1")), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("SO-2", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "-5", "1")), OPERATOR));
    }

    @Test
    void 单价为负拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "5", "-1")), OPERATOR));
    }

    @Test
    void 单价可为零() {
        SalesOrder order = service.create("SO-1", CUSTOMER, ORDER_DATE, null,
                List.of(line(P_A, "5", "0")), OPERATOR);
        assertEqualsDecimal("0.00", order.getLines().get(0).getAmount());
    }

    // ---------------------------------------------------------------
    // 状态机
    // ---------------------------------------------------------------

    @Test
    void 状态机_草稿到审核() {
        service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "10", "1")), OPERATOR);
        assertEquals(DocumentStatus.APPROVED, service.approve("SO-1", OPERATOR).getStatus());
    }

    @Test
    void 重复审核非法流转() {
        service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "10", "1")), OPERATOR);
        service.approve("SO-1", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.approve("SO-1", OPERATOR));
    }

    @Test
    void 草稿可作废_已审核作废非法() {
        service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "10", "1")), OPERATOR);
        assertEquals(DocumentStatus.CANCELLED, service.cancel("SO-1", OPERATOR).getStatus());

        service.create("SO-2", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "10", "1")), OPERATOR);
        service.approve("SO-2", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.cancel("SO-2", OPERATOR));
    }

    // ---------------------------------------------------------------
    // 累计发货量回写（出库单驱动）
    // ---------------------------------------------------------------

    @Test
    void 回写累计发货量_累加并更新剩余可发量() {
        service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "100", "1")), OPERATOR);
        service.approve("SO-1", OPERATOR);

        service.recordDelivery("SO-1", 1, new BigDecimal("30"));
        SalesOrder afterFirst = service.get("SO-1");
        assertEqualsDecimal("30", afterFirst.getLines().get(0).getDeliveredQty());
        assertEqualsDecimal("70", afterFirst.getLines().get(0).remainingQty());

        service.recordDelivery("SO-1", 1, new BigDecimal("70"));
        SalesOrder afterSecond = service.get("SO-1");
        assertEqualsDecimal("100", afterSecond.getLines().get(0).getDeliveredQty());
        assertEqualsDecimal("0", afterSecond.getLines().get(0).remainingQty());
    }

    @Test
    void 累计发货超过订单量拒绝() {
        service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "100", "1")), OPERATOR);
        service.approve("SO-1", OPERATOR);
        service.recordDelivery("SO-1", 1, new BigDecimal("80"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.recordDelivery("SO-1", 1, new BigDecimal("30")));
        assertTrue(ex.getMessage().contains("超过剩余可发量"), ex.getMessage());
        // 拒绝后累计发货量不变
        assertEqualsDecimal("80", service.get("SO-1").getLines().get(0).getDeliveredQty());
    }

    @Test
    void 查询不存在的订单抛NotFound() {
        assertThrows(SalesOrderNotFoundException.class, () -> service.get("SO-NONE"));
    }

    @Test
    void operator为空拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("SO-1", CUSTOMER, ORDER_DATE, null, List.of(line(P_A, "1", "1")), " "));
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static SalesOrderLineInput line(long productId, String quantity, String unitPrice) {
        return new SalesOrderLineInput(productId, new BigDecimal(quantity), new BigDecimal(unitPrice));
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
}
