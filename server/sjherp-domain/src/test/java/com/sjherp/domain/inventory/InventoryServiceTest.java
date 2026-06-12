package com.sjherp.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryBalanceRepository;
import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryTransactionRepository;

/**
 * 库存领域服务测试：负库存策略、幂等两路径、盘盈口径、成本调整约束、
 * BigDecimal 边界、校验规则、审计标注与摘要。
 */
class InventoryServiceTest {

    private static final String OPERATOR = "tester";
    private static final long WH1 = 1L;
    private static final long P = 100L;

    private InMemoryBalanceRepository balanceRepository;
    private InMemoryTransactionRepository transactionRepository;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        balanceRepository = new InMemoryBalanceRepository();
        transactionRepository = new InMemoryTransactionRepository();
        service = newService(InventoryPolicy.defaults());
    }

    private InventoryService newService(InventoryPolicy policy) {
        return new InventoryService(balanceRepository, transactionRepository,
                new MovingWeightedAverageCalculator(), policy);
    }

    private static BigDecimal num(String value) {
        return new BigDecimal(value);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, num(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual.toPlainString());
    }

    private static InboundCommand inboundCmd(InventoryTxnType type, String qty, String price, String key) {
        return new InboundCommand(WH1, P, type, num(qty), price == null ? null : num(price), null,
                "DOC", "D-1", 1, key);
    }

    private static OutboundCommand outboundCmd(InventoryTxnType type, String qty, String key) {
        return new OutboundCommand(WH1, P, type, num(qty), "DOC", "D-1", 1, key);
    }

    private static CostAdjustCommand adjustCmd(String amount, String key) {
        return new CostAdjustCommand(WH1, P, num(amount), "DOC", "D-1", 1, key);
    }

    // ---------------------------------------------------------------
    // 负库存策略（拆解 §1.5）
    // ---------------------------------------------------------------

    @Nested
    class 负库存策略 {

        @Test
        void 默认拒绝_异常含现存量与需求量_无任何写入() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "5.00", "k1"), OPERATOR);

            InsufficientStockException e = assertThrows(InsufficientStockException.class,
                    () -> service.outbound(outboundCmd(InventoryTxnType.SALES_OUT, "15", "k2"), OPERATOR));
            assertEquals(WH1, e.getWarehouseId());
            assertEquals(P, e.getProductId());
            assertNum("10", e.getAvailable());
            assertNum("15", e.getRequested());
            assertTrue(e.getMessage().contains("10") && e.getMessage().contains("15"), e.getMessage());
            assertEquals(1, transactionRepository.store.size(), "拒绝出库不得产生流水");
            assertNum("10", balanceRepository.quantityOf(WH1, P));
        }

        @Test
        void 无流水无余额时出库_默认拒绝() {
            assertThrows(InsufficientStockException.class,
                    () -> service.outbound(outboundCmd(InventoryTxnType.SALES_OUT, "1", "k1"), OPERATOR));
        }

        @Test
        void 开关放行_出库前有存量照常加权_可打穿至负库存() {
            InventoryService allowing = newService(new InventoryPolicy(true));
            allowing.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "5.00", "k1"), OPERATOR);

            // 出库前 quantity = 10 > 0：照常加权 5.000000 × 15 = 75.00
            StockMovementResult r = allowing.outbound(
                    outboundCmd(InventoryTxnType.SALES_OUT, "15", "k2"), OPERATOR);
            assertNum("5.000000", r.unitCost());
            assertNum("-75.00", r.totalCost());
            assertNum("-5", r.balanceQuantityAfter());
            assertNum("-25.00", r.balanceAmountAfter());
        }

        @Test
        void 开关放行_出库前数量为负_成本退化取最近一笔带单价流水() {
            InventoryService allowing = newService(new InventoryPolicy(true));
            allowing.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "5.00", "k1"), OPERATOR);
            allowing.outbound(outboundCmd(InventoryTxnType.SALES_OUT, "15", "k2"), OPERATOR);

            // 余额 (-5, -25.00)：单价无法加权 → 取最近一笔带单价流水（k2 出库，单价 5.000000）
            StockMovementResult r = allowing.outbound(
                    outboundCmd(InventoryTxnType.SALES_OUT, "5", "k3"), OPERATOR);
            assertNum("5.000000", r.unitCost());
            assertNum("-25.00", r.totalCost());
            assertNum("-10", r.balanceQuantityAfter());
            assertNum("-50.00", r.balanceAmountAfter());
        }

        @Test
        void 开关放行但连流水都没有_仍拒绝() {
            InventoryService allowing = newService(new InventoryPolicy(true));
            InsufficientStockException e = assertThrows(InsufficientStockException.class,
                    () -> allowing.outbound(outboundCmd(InventoryTxnType.SALES_OUT, "5", "k1"), OPERATOR));
            assertTrue(e.getMessage().contains("无法确定出库成本"), e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // 幂等两路径（拆解 §1.3）
    // ---------------------------------------------------------------

    @Nested
    class 幂等 {

        @Test
        void 同键同参_返回首次结果_不重复写入() {
            InboundCommand cmd = inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "5.00", "dup");
            StockMovementResult first = service.inbound(cmd, OPERATOR);
            StockMovementResult replayed = service.inbound(cmd, OPERATOR);

            assertEquals(first, replayed, "重试应返回首次过账结果");
            assertEquals(1, transactionRepository.store.size(), "重试不得产生第二笔流水");
            assertNum("10", balanceRepository.quantityOf(WH1, P));
            assertNum("50.00", balanceRepository.amountOf(WH1, P));
        }

        @Test
        void 同键不同参_抛幂等冲突_不写入() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "5.00", "dup"), OPERATOR);

            IdempotencyConflictException e = assertThrows(IdempotencyConflictException.class,
                    () -> service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "20", "5.00", "dup"), OPERATOR));
            assertEquals("dup", e.getIdempotencyKey());
            assertEquals(1, transactionRepository.store.size());
            assertNum("10", balanceRepository.quantityOf(WH1, P));
        }

        @Test
        void 同键不同类型_同样视为冲突() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "5.00", "dup"), OPERATOR);
            assertThrows(IdempotencyConflictException.class,
                    () -> service.outbound(outboundCmd(InventoryTxnType.SALES_OUT, "10", "dup"), OPERATOR));
        }

        @Test
        void 出库重试_返回首次COGS() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "5.00", "k1"), OPERATOR);
            OutboundCommand cmd = outboundCmd(InventoryTxnType.SALES_OUT, "4", "k2");
            StockMovementResult first = service.outbound(cmd, OPERATOR);
            StockMovementResult replayed = service.outbound(cmd, OPERATOR);
            assertEquals(first, replayed);
            assertNum("-20.00", replayed.totalCost());
            assertEquals(2, transactionRepository.store.size());
        }
    }

    // ---------------------------------------------------------------
    // 盘盈口径（拆解 §1.6.1）
    // ---------------------------------------------------------------

    @Nested
    class 盘盈 {

        @Test
        void 有存量盘盈_默认按当前加权单价入库() {
            service.inbound(inboundCmd(InventoryTxnType.OPENING, "100", "10.00", "k1"), OPERATOR);
            StockMovementResult r = service.inbound(
                    inboundCmd(InventoryTxnType.COUNT_GAIN, "10", null, "k2"), OPERATOR);
            assertNum("10.000000", r.unitCost());
            assertNum("100.00", r.totalCost());
            assertNum("110", r.balanceQuantityAfter());
            assertNum("1100.00", r.balanceAmountAfter());
        }

        @Test
        void 零库存盘盈不指定成本_拒绝() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.inbound(inboundCmd(InventoryTxnType.COUNT_GAIN, "10", null, "k1"), OPERATOR));
            assertTrue(e.getMessage().contains("零库存盘盈必须指定成本"), e.getMessage());
            assertEquals(0, transactionRepository.store.size());
        }

        @Test
        void 零库存盘盈指定成本_放行() {
            StockMovementResult r = service.inbound(
                    inboundCmd(InventoryTxnType.COUNT_GAIN, "10", "3.50", "k1"), OPERATOR);
            assertNum("3.500000", r.unitCost());
            assertNum("35.00", r.totalCost());
        }
    }

    // ---------------------------------------------------------------
    // 成本调整约束（拆解 §1.6.4）
    // ---------------------------------------------------------------

    @Nested
    class 成本调整 {

        @Test
        void 调整到金额恰好为零_放行() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "1.00", "k1"), OPERATOR);
            StockMovementResult r = service.adjustCost(adjustCmd("-10.00", "k2"), OPERATOR);
            assertNum("0", r.quantity());
            assertNull(r.unitCost());
            assertNum("-10.00", r.totalCost());
            assertNum("10", r.balanceQuantityAfter());
            assertNum("0.00", r.balanceAmountAfter());
        }

        @Test
        void 调整后金额为负_拒绝() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "1.00", "k1"), OPERATOR);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.adjustCost(adjustCmd("-10.01", "k2"), OPERATOR));
            assertTrue(e.getMessage().contains("不能为负"), e.getMessage());
            assertNum("10.00", balanceRepository.amountOf(WH1, P));
        }

        @Test
        void 零库存调整_拒绝() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.adjustCost(adjustCmd("1.00", "k1"), OPERATOR));
        }

        @Test
        void 调整额为零或超过两位小数_拒绝() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "1.00", "k1"), OPERATOR);
            assertThrows(IllegalArgumentException.class,
                    () -> service.adjustCost(adjustCmd("0", "k2"), OPERATOR));
            assertThrows(IllegalArgumentException.class,
                    () -> service.adjustCost(adjustCmd("0.001", "k3"), OPERATOR));
        }

        @Test
        void 调整即时改变后续出库加权单价() {
            service.inbound(inboundCmd(InventoryTxnType.PURCHASE_IN, "10", "1.00", "k1"), OPERATOR);
            service.adjustCost(adjustCmd("5.00", "k2"), OPERATOR);
            // 调整后余额 (10, 15.00) → 出库单价 1.500000
            StockMovementResult r = service.outbound(
                    outboundCmd(InventoryTxnType.SALES_OUT, "2", "k3"), OPERATOR);
            assertNum("1.500000", r.unitCost());
            assertNum("-3.00", r.totalCost());
        }
    }

    // ---------------------------------------------------------------
    // BigDecimal 边界与校验
    // ---------------------------------------------------------------

    @Nested
    class 边界与校验 {

        @Test
        void 零数量与负数量_拒绝() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "0", "5.00", "k1"), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.outbound(
                    outboundCmd(InventoryTxnType.SALES_OUT, "-1", "k2"), OPERATOR));
        }

        @Test
        void 数量超过6位小数_拒绝_恰好6位放行() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "0.0000001", "5.00", "k1"), OPERATOR));
            StockMovementResult r = service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "0.000001", "5.00", "k2"), OPERATOR);
            assertNum("0.000001", r.quantity());
        }

        @Test
        void 单价6位小数放行_金额舍入到2位_全程非负() {
            // 3 × 0.000001 = 0.000003 → 0.00：金额舍入在 total 一步，余额仍非负
            StockMovementResult r = service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "3", "0.000001", "k1"), OPERATOR);
            assertNum("0.000001", r.unitCost());
            assertNum("0.00", r.totalCost());
            assertNum("3", r.balanceQuantityAfter());
            assertNum("0.00", r.balanceAmountAfter());
            assertTrue(r.balanceAmountAfter().signum() >= 0);
        }

        @Test
        void 单价超过6位小数或为负_拒绝() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "1", "0.0000001", "k1"), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "1", "-1.00", "k2"), OPERATOR));
        }

        @Test
        void 非盘盈入库缺单价_拒绝() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "1", null, "k1"), OPERATOR));
        }

        @Test
        void 流水类型方向与方法不匹配_拒绝() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.SALES_OUT, "1", "5.00", "k1"), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.outbound(
                    outboundCmd(InventoryTxnType.PURCHASE_IN, "1", "k2"), OPERATOR));
        }

        @Test
        void 幂等键与来源单据必填() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "1", "5.00", null), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "1", "5.00", "  "), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    new InboundCommand(WH1, P, InventoryTxnType.PURCHASE_IN, num("1"), num("5.00"),
                            null, null, "D-1", 1, "k1"), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    new InboundCommand(WH1, P, InventoryTxnType.PURCHASE_IN, num("1"), num("5.00"),
                            null, "DOC", null, 1, "k2"), OPERATOR));
        }

        @Test
        void operator为空_拒绝() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "1", "5.00", "k1"), null));
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    inboundCmd(InventoryTxnType.PURCHASE_IN, "1", "5.00", "k1"), " "));
        }

        @Test
        void 非调拨入禁填transferOutKey_调拨入禁填单价() {
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    new InboundCommand(WH1, P, InventoryTxnType.PURCHASE_IN, num("1"), num("5.00"),
                            "some-key", "DOC", "D-1", 1, "k1"), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    new InboundCommand(WH1, P, InventoryTxnType.TRANSFER_IN, num("1"), num("5.00"),
                            "out-key", "DOC", "D-1", 1, "k2"), OPERATOR));
            assertThrows(IllegalArgumentException.class, () -> service.inbound(
                    new InboundCommand(WH1, P, InventoryTxnType.TRANSFER_IN, num("1"), null,
                            null, "DOC", "D-1", 1, "k3"), OPERATOR));
        }
    }

    // ---------------------------------------------------------------
    // 只读查询与审计
    // ---------------------------------------------------------------

    @Test
    void balanceOf_无余额行返回零视图_派生单价为null() {
        InventoryBalanceView view = service.balanceOf(WH1, P);
        assertNum("0", view.quantity());
        assertNum("0.00", view.costAmount());
        assertNull(view.derivedUnitCost());
    }

    @Test
    void 审计目标_摘要含关键字段且不超长() {
        StockMovementResult r = service.inbound(
                inboundCmd(InventoryTxnType.PURCHASE_IN, "50", "12.50", "PR:PO-1:1"), OPERATOR);
        assertEquals(r.transactionId(), r.auditTargetId());
        assertEquals("PR:PO-1:1", r.auditTargetCode());
        String summary = r.auditSummary();
        assertTrue(summary.contains("采购入库") && summary.contains("625.00"), summary);
        assertTrue(summary.length() <= 2000, "摘要不得超过审计截断长度: " + summary.length());
    }

    @Test
    void 写方法均已标注Audited_动作符合约定() throws NoSuchMethodException {
        assertAudited("inbound", "inventory.inbound", InboundCommand.class);
        assertAudited("outbound", "inventory.outbound", OutboundCommand.class);
        assertAudited("adjustCost", "inventory.adjust_cost", CostAdjustCommand.class);
        assertAudited("execute", "inventory.execute", List.class);
    }

    private static void assertAudited(String methodName, String expectedAction,
                                      Class<?> commandType) throws NoSuchMethodException {
        Method method = InventoryService.class.getMethod(methodName, commandType, String.class);
        Audited audited = method.getAnnotation(Audited.class);
        assertNotNull(audited, methodName + " 必须标注 @Audited（每笔写操作必有审计记录）");
        assertEquals(expectedAction, audited.action());
        assertEquals("inventory", audited.targetType());
    }
}
