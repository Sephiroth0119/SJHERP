package com.sjherp.domain.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryBalanceRepository;
import com.sjherp.domain.inventory.InMemoryInventoryFixtures.InMemoryTransactionRepository;

/**
 * 教科书对账案例（拆解 §2）：商品 P（个），仓库 WH1，10 步逐行断言全部数字
 * （流水 quantity/unit_cost/total_cost 与余额数量/金额精确比对，含验算点步 4/6/8/9），
 * 跑完后验证对账恒等式 Σ流水 quantity = 20、Σtotal_cost = 160.00 = 余额。
 */
class InventoryLedgerCaseTest {

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
        service = new InventoryService(balanceRepository, transactionRepository,
                new MovingWeightedAverageCalculator(), InventoryPolicy.defaults());
    }

    // ---------------------------------------------------------------
    // 指令辅助
    // ---------------------------------------------------------------

    private static BigDecimal num(String value) {
        return new BigDecimal(value);
    }

    private StockMovementResult in(int step, InventoryTxnType type, String qty, String price) {
        return service.inbound(new InboundCommand(WH1, P, type, num(qty),
                price == null ? null : num(price), null,
                "CASE_DOC", "CD-202606-000" + step, 1, "CASE_DOC:CD-202606-000" + step + ":1"), OPERATOR);
    }

    private StockMovementResult out(int step, InventoryTxnType type, String qty) {
        return service.outbound(new OutboundCommand(WH1, P, type, num(qty),
                "CASE_DOC", "CD-202606-000" + step, 1, "CASE_DOC:CD-202606-000" + step + ":1"), OPERATOR);
    }

    private StockMovementResult adjust(int step, String amount) {
        return service.adjustCost(new CostAdjustCommand(WH1, P, num(amount),
                "CASE_DOC", "CD-202606-000" + step, 1, "CASE_DOC:CD-202606-000" + step + ":1"), OPERATOR);
    }

    // ---------------------------------------------------------------
    // 断言辅助：数值精确比对（compareTo，0 视角）+ 精度位数断言
    // ---------------------------------------------------------------

    private static void assertNum(String expected, BigDecimal actual, String field) {
        assertNotNull(actual, field + " 不应为 null");
        assertEquals(0, num(expected).compareTo(actual),
                field + " 期望 " + expected + "，实际 " + actual.toPlainString());
    }

    /** 逐行断言一步：流水带符号数量/单价快照/带符号金额 + 过账后余额数量/金额 */
    private static void assertStep(StockMovementResult r, String qty, String unitCost,
                                   String total, String balQty, String balAmt) {
        assertNum(qty, r.quantity(), "流水 quantity");
        if (unitCost == null) {
            assertNull(r.unitCost(), "成本调整流水 unit_cost 应为 NULL");
        } else {
            assertNum(unitCost, r.unitCost(), "流水 unit_cost");
            assertEquals(6, r.unitCost().scale(), "unit_cost 固定 6 位小数");
        }
        assertNum(total, r.totalCost(), "流水 total_cost");
        assertEquals(2, r.totalCost().scale(), "total_cost 固定 2 位小数");
        assertNum(balQty, r.balanceQuantityAfter(), "余额数量");
        assertNum(balAmt, r.balanceAmountAfter(), "余额金额");
        // 全程 cost_amount >= 0（负库存关闭时不存在负成本状态，拆解 §1.5）
        assertTrue(r.balanceAmountAfter().signum() >= 0,
                "结存金额不得为负: " + r.balanceAmountAfter().toPlainString());
        assertTrue(r.balanceQuantityAfter().signum() >= 0,
                "默认策略下结存数量不得为负: " + r.balanceQuantityAfter().toPlainString());
    }

    private void assertDerivedUnitCost(String expected) {
        BigDecimal derived = service.balanceOf(WH1, P).derivedUnitCost();
        if (expected == null) {
            assertNull(derived, "数量为 0 时派生单价应为 null（展示为 —）");
        } else {
            assertNum(expected, derived, "派生加权单价");
        }
    }

    // ---------------------------------------------------------------
    // 案例本体
    // ---------------------------------------------------------------

    @Test
    void 教科书案例10步逐行断言_含验算点与对账恒等式() {
        // 步 1：期初 100 个 @10.00
        assertStep(in(1, InventoryTxnType.OPENING, "100", "10.00"),
                "100", "10.000000", "1000.00", "100", "1000.00");
        assertDerivedUnitCost("10.000000");

        // 步 2：采购入库 50 @12.50
        assertStep(in(2, InventoryTxnType.PURCHASE_IN, "50", "12.50"),
                "50", "12.500000", "625.00", "150", "1625.00");
        assertDerivedUnitCost("10.833333");

        // 步 3：采购入库 30 @11.20
        assertStep(in(3, InventoryTxnType.PURCHASE_IN, "30", "11.20"),
                "30", "11.200000", "336.00", "180", "1961.00");
        assertDerivedUnitCost("10.894444");

        // 步 4（验算点）：销售出库 70 → unit_cost = 1961.00/180 = 10.894444，
        // total = 762.61108 → 762.61，余额 1961.00 − 762.61 = 1198.39
        assertStep(out(4, InventoryTxnType.SALES_OUT, "70"),
                "-70", "10.894444", "-762.61", "110", "1198.39");
        // 扣减后派生单价 1198.39/110 = 10.894455 ≠ 出库时点 10.894444：
        // 正常漂移而非 bug（单价不冗余存储的论据，拆解 §1.1/§2）
        assertDerivedUnitCost("10.894455");

        // 补充断言（拆解 §2）：此时点尝试出库超过现存量 → 异常含现存量/需求量，余额与流水均无变化
        InsufficientStockException insufficient = assertThrows(InsufficientStockException.class,
                () -> out(99, InventoryTxnType.SALES_OUT, "200"));
        assertNum("110", insufficient.getAvailable(), "异常携带现存量");
        assertNum("200", insufficient.getRequested(), "异常携带需求量");
        assertTrue(insufficient.getMessage().contains("110"), "文案含现存量: " + insufficient.getMessage());
        assertTrue(insufficient.getMessage().contains("200"), "文案含需求量: " + insufficient.getMessage());
        assertEquals(4, transactionRepository.store.size(), "拒绝出库不得产生流水");
        assertNum("110", service.balanceOf(WH1, P).quantity(), "拒绝出库余额数量不变");
        assertNum("1198.39", service.balanceOf(WH1, P).costAmount(), "拒绝出库余额金额不变");

        // 步 5：采购入库 40 @9.80
        assertStep(in(5, InventoryTxnType.PURCHASE_IN, "40", "9.80"),
                "40", "9.800000", "392.00", "150", "1590.39");
        assertDerivedUnitCost("10.602600");

        // 步 6（验算点）：盘亏 5 → unit_cost = 1590.39/150 = 10.602600（整除），
        // total = 53.013 → 53.01（HALF_UP 第三位 3 舍）
        assertStep(out(6, InventoryTxnType.COUNT_LOSS, "5"),
                "-5", "10.602600", "-53.01", "145", "1537.38");
        assertDerivedUnitCost("10.602621");

        // 步 7：成本调整 +12.62（运费入成本）→ 数量不变，unit_cost NULL
        assertStep(adjust(7, "12.62"),
                "0", null, "12.62", "145", "1550.00");
        assertDerivedUnitCost("10.689655");

        // 步 8（验算点）：销售出库 100 → unit_cost = 1550.00/145 = 10.689655，
        // total = 1068.9655 → 1068.97（HALF_UP 进位）
        assertStep(out(8, InventoryTxnType.SALES_OUT, "100"),
                "-100", "10.689655", "-1068.97", "45", "481.03");
        assertDerivedUnitCost("10.689556");

        // 步 9（验算点，出空清零）：销售出库 45 → unit_cost = 481.03/45 = 10.689556，
        // total 走清零规则直接取出库前 cost_amount = 481.03（本例与公式结果恰好一致，
        // 规则路径本身由 MovingWeightedAverageCalculatorTest 的构造用例单独验证）
        assertStep(out(9, InventoryTxnType.SALES_OUT, "45"),
                "-45", "10.689556", "-481.03", "0", "0.00");
        assertDerivedUnitCost(null);

        // 补充断言（拆解 §2）：quantity = 0 时成本调整 → 拒绝
        IllegalArgumentException adjustRejected = assertThrows(IllegalArgumentException.class,
                () -> adjust(98, "1.00"));
        assertTrue(adjustRejected.getMessage().contains("结存数量大于 0"), adjustRejected.getMessage());

        // 步 10：零库存后采购入库 20 @8.00 → 单价自然 = 本次入库价（§1.6.3）
        assertStep(in(10, InventoryTxnType.PURCHASE_IN, "20", "8.00"),
                "20", "8.000000", "160.00", "20", "160.00");
        assertDerivedUnitCost("8.000000");

        // ------------------------------------------------------------
        // 对账恒等式（拆解 §2）：Σ流水 quantity = 20 = 期末数量；
        // Σ流水 total_cost = 160.00 = 期末金额
        // ------------------------------------------------------------
        assertEquals(10, transactionRepository.store.size(), "10 步应恰好产生 10 笔流水");
        BigDecimal sumQuantity = transactionRepository.store.stream()
                .map(InventoryTransaction::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumTotalCost = transactionRepository.store.stream()
                .map(InventoryTransaction::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertNum("20", sumQuantity, "Σ流水 quantity");
        assertNum("160.00", sumTotalCost, "Σ流水 total_cost");
        InventoryBalanceView balance = service.balanceOf(WH1, P);
        assertEquals(0, sumQuantity.compareTo(balance.quantity()), "Σ流水数量 = 余额数量");
        assertEquals(0, sumTotalCost.compareTo(balance.costAmount()), "Σ流水金额 = 余额金额");

        // 全程余额快照 cost_amount >= 0（每笔流水的过账后快照逐一复核）
        for (InventoryTransaction txn : transactionRepository.store) {
            assertTrue(txn.getBalanceAmountAfter().signum() >= 0,
                    "流水[" + txn.getIdempotencyKey() + "] 过账后金额为负: "
                            + txn.getBalanceAmountAfter().toPlainString());
        }
    }

    @Test
    void 出空清零规则路径_经服务整链验证_构造公式结果不等于余额() {
        // 入 1000000 个 @0.000001 → total = 1.00；成本调整 −0.99 → 余额 (1000000, 0.01)
        in(1, InventoryTxnType.OPENING, "1000000", "0.000001");
        adjust(2, "-0.99");
        assertNum("0.01", service.balanceOf(WH1, P).costAmount(), "构造后余额金额");

        // 全量出库：公式口径 total = 0.000000 × 1000000 = 0.00，清零规则带走 0.01
        StockMovementResult result = out(3, InventoryTxnType.SALES_OUT, "1000000");
        assertNum("-0.01", result.totalCost(), "清零规则全额带走（公式口径会算出 0.00）");
        assertNum("0", result.balanceQuantityAfter(), "出空后数量");
        assertNum("0.00", result.balanceAmountAfter(), "出空后金额（不残留尾差行）");
    }
}
