package com.sjherp.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryBalanceRepository;
import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryTransactionRepository;

/**
 * 批量过账测试（拆解 §1.3/§1.4/§1.6.5）：锁顺序升序约定、批量原子语义、
 * 调拨两腿金额守恒。
 */
class InventoryServiceBatchTest {

    private static final String OPERATOR = "tester";

    private InMemoryBalanceRepository balanceRepository;
    private InMemoryTransactionRepository transactionRepository;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        balanceRepository = new InMemoryBalanceRepository();
        transactionRepository = new InMemoryTransactionRepository();
        service = new InventoryService(balanceRepository, transactionRepository,
                new MovingWeightedAverageCalculator(), InventoryPolicy.defaults());
    }

    private static BigDecimal num(String value) {
        return new BigDecimal(value);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, num(expected).compareTo(actual),
                "期望 " + expected + "，实际 " + actual.toPlainString());
    }

    private static InboundCommand in(long warehouseId, long productId, String qty, String price, String key) {
        return new InboundCommand(warehouseId, productId, InventoryTxnType.PURCHASE_IN,
                num(qty), num(price), null, "DOC", "D-1", 1, key);
    }

    // ---------------------------------------------------------------
    // 锁顺序（拆解 §1.4：升序加锁防死锁，T01d 真库回归的领域层前置）
    // ---------------------------------------------------------------

    @Test
    void 批量过账_对涉及余额行去重后按仓库商品升序加锁() {
        List<StockMovementCommand> batch = List.of(
                in(2L, 5L, "1", "1.00", "k1"),
                in(1L, 9L, "1", "1.00", "k2"),
                in(2L, 1L, "1", "1.00", "k3"),
                in(1L, 9L, "1", "1.00", "k4"));  // (1,9) 重复涉及：去重只锁一次

        service.execute(batch, OPERATOR);

        assertEquals(List.of("1:9", "2:1", "2:5"), balanceRepository.lockCalls,
                "加锁顺序必须是 (warehouseId, productId) 去重升序");
        assertEquals(4, transactionRepository.store.size(), "4 条指令全部过账");
        assertNum("2", balanceRepository.quantityOf(1L, 9L));
    }

    @Test
    void 单笔写入口同样经lockForUpdate锁行() {
        service.inbound(in(3L, 7L, "1", "1.00", "k1"), OPERATOR);
        assertEquals(List.of("3:7"), balanceRepository.lockCalls);
    }

    // ---------------------------------------------------------------
    // 批量原子语义：异常向上抛（由 app 层事务整体回滚），失败后不再继续后续条目
    // ---------------------------------------------------------------

    @Test
    void 批量中途失败_异常上抛_后续条目不再执行() {
        transactionRepository.failOnSaveAt = 2; // 第 2 条流水落库时失败

        List<StockMovementCommand> batch = List.of(
                in(1L, 1L, "10", "1.00", "k1"),
                in(1L, 2L, "10", "1.00", "k2"),
                in(1L, 3L, "10", "1.00", "k3"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.execute(batch, OPERATOR));
        assertTrue(e.getMessage().contains("模拟流水落库失败"), e.getMessage());

        // 领域层语义：异常原样上抛（真实环境由 @Transactional 整体回滚已写入的第 1 条）；
        // 这里断言失败点之后的条目绝不继续执行——无第 3 条流水、第 3 行余额未被过账
        assertEquals(1, transactionRepository.store.size(), "失败后不得继续产生流水");
        assertTrue(transactionRepository.findByIdempotencyKey("k3").isEmpty());
        assertNum("0", balanceRepository.quantityOf(1L, 3L));
    }

    @Test
    void 批量校验先行_任一条非法则整批拒绝_零写入() {
        List<StockMovementCommand> batch = List.of(
                in(1L, 1L, "10", "1.00", "k1"),
                in(1L, 2L, "0", "1.00", "k2"));  // 0 数量非法

        assertThrows(IllegalArgumentException.class, () -> service.execute(batch, OPERATOR));
        assertEquals(0, transactionRepository.store.size(), "校验失败不得有任何写入");
        assertEquals(0, balanceRepository.lockCalls.size(), "校验失败不得加任何行锁");
    }

    @Test
    void 空批量_拒绝() {
        assertThrows(IllegalArgumentException.class, () -> service.execute(List.of(), OPERATOR));
    }

    // ---------------------------------------------------------------
    // 调拨：一出一入同批次，金额守恒（拆解 §1.6.5）
    // ---------------------------------------------------------------

    @Test
    void 调拨两腿同批次_调入取调出原值_金额守恒() {
        // WH1 备货：100@10.00 + 50@12.50 → (150, 1625.00)，加权 10.833333
        service.inbound(new InboundCommand(1L, 100L, InventoryTxnType.OPENING, num("100"),
                num("10.00"), null, "OPENING", "OP-1", 1, "op1"), OPERATOR);
        service.inbound(in(1L, 100L, "50", "12.50", "po1"), OPERATOR);

        String outKey = "TRANSFER:TR-1:1:OUT";
        List<StockMovementResult> results = service.execute(List.of(
                new OutboundCommand(1L, 100L, InventoryTxnType.TRANSFER_OUT, num("30"),
                        "TRANSFER", "TR-1", 1, outKey),
                new InboundCommand(2L, 100L, InventoryTxnType.TRANSFER_IN, num("30"),
                        null, outKey, "TRANSFER", "TR-1", 1, "TRANSFER:TR-1:1:IN")), OPERATOR);

        StockMovementResult outLeg = results.get(0);
        StockMovementResult inLeg = results.get(1);
        // 调出：10.833333 × 30 = 324.99999 → 325.00
        assertNum("10.833333", outLeg.unitCost());
        assertNum("-325.00", outLeg.totalCost());
        assertNum("120", outLeg.balanceQuantityAfter());
        assertNum("1300.00", outLeg.balanceAmountAfter());
        // 调入：用调出原值（不重新加权舍入），单价快照同调出
        assertNum("10.833333", inLeg.unitCost());
        assertNum("325.00", inLeg.totalCost());
        assertNum("30", inLeg.balanceQuantityAfter());
        assertNum("325.00", inLeg.balanceAmountAfter());
        // 金额守恒：两仓金额之和 = 调拨前 WH1 金额
        assertNum("1625.00", balanceRepository.amountOf(1L, 100L)
                .add(balanceRepository.amountOf(2L, 100L)));
        // 锁顺序：先备货两次锁 (1,100)，批量内 (1,100) < (2,100) 升序
        assertEquals(List.of("1:100", "1:100", "1:100", "2:100"), balanceRepository.lockCalls);
    }

    @Test
    void 调拨入分次调用_从已落库调出流水取原值() {
        service.inbound(in(1L, 100L, "10", "5.00", "po1"), OPERATOR);
        String outKey = "TRANSFER:TR-2:1:OUT";
        service.outbound(new OutboundCommand(1L, 100L, InventoryTxnType.TRANSFER_OUT, num("4"),
                "TRANSFER", "TR-2", 1, outKey), OPERATOR);

        StockMovementResult inLeg = service.inbound(new InboundCommand(2L, 100L,
                InventoryTxnType.TRANSFER_IN, num("4"), null, outKey,
                "TRANSFER", "TR-2", 1, "TRANSFER:TR-2:1:IN"), OPERATOR);
        assertNum("5.000000", inLeg.unitCost());
        assertNum("20.00", inLeg.totalCost());
    }

    @Test
    void 调拨入数量与调出不一致_拒绝() {
        service.inbound(in(1L, 100L, "10", "5.00", "po1"), OPERATOR);
        String outKey = "TRANSFER:TR-3:1:OUT";
        service.outbound(new OutboundCommand(1L, 100L, InventoryTxnType.TRANSFER_OUT, num("4"),
                "TRANSFER", "TR-3", 1, outKey), OPERATOR);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.inbound(new InboundCommand(2L, 100L, InventoryTxnType.TRANSFER_IN,
                        num("5"), null, outKey, "TRANSFER", "TR-3", 1, "TRANSFER:TR-3:1:IN"), OPERATOR));
        assertTrue(e.getMessage().contains("数量必须与调出一致"), e.getMessage());
    }

    @Test
    void 调拨入找不到调出流水或引用非调出流水_拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.inbound(new InboundCommand(2L, 100L, InventoryTxnType.TRANSFER_IN,
                        num("5"), null, "missing-key", "TRANSFER", "TR-4", 1, "in-key"), OPERATOR));

        service.inbound(in(1L, 100L, "10", "5.00", "po1"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.inbound(new InboundCommand(2L, 100L, InventoryTxnType.TRANSFER_IN,
                        num("10"), null, "po1", "TRANSFER", "TR-4", 1, "in-key2"), OPERATOR));
        assertTrue(e.getMessage().contains("不是调拨出"), e.getMessage());
    }
}
