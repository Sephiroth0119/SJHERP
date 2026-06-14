package com.sjherp.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryBalanceRepository;
import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryTransactionRepository;

/**
 * 库存红冲按原成本反向出库基元单测（M4-T07b 共享基元 1，设计真源 §1.6/§2）。
 *
 * <p>覆盖 {@link OutboundCommand#overriddenUnitCost}：
 * <ul>
 *   <li>非空时跳过移动加权，严格按指定单价算 totalCost（期间进新货改变加权也不受影响）；</li>
 *   <li>为空时走原移动加权路径，行为完全不变；</li>
 *   <li>指定单价为负拒绝；同键重放须同 overriddenUnitCost，否则 {@link IdempotencyConflictException}。</li>
 * </ul>
 */
class InventoryServiceReversalCostTest {

    private static final String OPERATOR = "tester";
    private static final long WH = 1L;
    private static final long P = 100L;

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

    private void inbound(String qty, String price, String key) {
        service.inbound(new InboundCommand(WH, P, InventoryTxnType.PURCHASE_IN, num(qty),
                num(price), null, "PR", "PR-1", 1, key), OPERATOR);
    }

    private OutboundCommand reversalOut(String qty, String overriddenUnitCost, String key) {
        return new OutboundCommand(WH, P, InventoryTxnType.SALES_OUT, num(qty), "PR", "PR-1", 1,
                key, num(overriddenUnitCost));
    }

    // ---------------------------------------------------------------
    // overriddenUnitCost 非空：按指定成本反向，不读移动加权余额
    // ---------------------------------------------------------------

    @Test
    void 指定出库单价_严格按该单价算totalCost_不走移动加权() {
        // 进货 10 @ 12.50 → 余额数量 10、金额 125.00
        inbound("10", "12.50", "in-1");
        // 红冲按原成本 12.50 反向出 4
        StockMovementResult result = service.outbound(reversalOut("4", "12.50", "REVERSAL:PR-1:1"), OPERATOR);

        assertNum("-4", result.quantity());           // 出库流水数量为负
        assertNum("12.50", result.unitCost());
        assertNum("-50.00", result.totalCost());      // -(12.50 × 4)
        assertNum("6", balanceRepository.quantityOf(WH, P));
        assertNum("75.00", balanceRepository.amountOf(WH, P));
    }

    @Test
    void 期间已进新货改变加权_红冲仍按原固化单价反向() {
        // 先进 10 @ 12.50（原入库），再进 10 @ 20.00（期间新货，加权单价变为 16.25）
        inbound("10", "12.50", "in-1");
        inbound("10", "20.00", "in-2");
        assertNum("16.25", new MovingWeightedAverageCalculator()
                .weightedUnitCost(balanceRepository.quantityOf(WH, P), balanceRepository.amountOf(WH, P)));

        // 红冲原入库（10 @ 12.50）：必须按原 12.50 反向，绝不按当前加权 16.25
        StockMovementResult result = service.outbound(reversalOut("10", "12.50", "REVERSAL:PR-1:1"), OPERATOR);
        assertNum("12.50", result.unitCost());
        assertNum("-125.00", result.totalCost());
    }

    @Test
    void 指定出库单价为负_拒绝() {
        inbound("10", "12.50", "in-1");
        assertThrows(IllegalArgumentException.class,
                () -> service.outbound(reversalOut("1", "-0.01", "REVERSAL:PR-1:1"), OPERATOR));
        // 拒绝不得产生出库流水（仅 1 笔入库）
        assertEquals(1, transactionRepository.store.size());
    }

    @Test
    void 同键重放须同overriddenUnitCost_不同单价抛幂等冲突() {
        inbound("10", "12.50", "in-1");
        service.outbound(reversalOut("4", "12.50", "REVERSAL:PR-1:1"), OPERATOR);
        // 同幂等键、不同指定单价 → 幂等冲突（防同键不同成本被静默吞）
        assertThrows(IdempotencyConflictException.class,
                () -> service.outbound(reversalOut("4", "9.99", "REVERSAL:PR-1:1"), OPERATOR));
    }

    @Test
    void 同键同参重放_返回首次结果_不重复落流水() {
        inbound("10", "12.50", "in-1");
        StockMovementResult first = service.outbound(reversalOut("4", "12.50", "REVERSAL:PR-1:1"), OPERATOR);
        StockMovementResult replay = service.outbound(reversalOut("4", "12.50", "REVERSAL:PR-1:1"), OPERATOR);

        assertEquals(first.transactionId(), replay.transactionId());
        // 入库 1 + 出库 1 = 2 笔，重放不新增
        assertEquals(2, transactionRepository.store.size());
        assertNum("6", balanceRepository.quantityOf(WH, P));
    }

    // ---------------------------------------------------------------
    // overriddenUnitCost 为空：移动加权路径行为不变（对照组）
    // ---------------------------------------------------------------

    @Test
    void 未指定出库单价_走移动加权_成本为当前加权单价() {
        inbound("10", "12.50", "in-1");
        inbound("10", "20.00", "in-2");   // 加权后单价 16.25

        // 不带 overriddenUnitCost（8 参兼容构造）→ 移动加权
        StockMovementResult result = service.outbound(new OutboundCommand(WH, P,
                InventoryTxnType.SALES_OUT, num("4"), "SD", "SD-1", 1, "out-1"), OPERATOR);
        assertNum("16.25", result.unitCost());
        assertNum("-65.00", result.totalCost());   // -(16.25 × 4)
    }
}
