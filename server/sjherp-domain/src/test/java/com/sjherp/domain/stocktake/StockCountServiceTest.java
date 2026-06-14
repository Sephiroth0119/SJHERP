package com.sjherp.domain.stocktake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 盘点单领域服务单测（M3-T03）：状态机全路径、差异计算、盘盈成本口径（含零库存必填成本）、
 * 过账批量原子、幂等键与来源单据约定。用内存替身仓储 + 可桩库存端口，不依赖 Spring/DB。
 */
class StockCountServiceTest {

    private static final long WH = 1L;
    private static final long P_A = 100L;
    private static final long P_B = 200L;
    private static final long P_C = 300L;
    private static final String OPERATOR = "tester";

    private FakeStockCountRepository repository;
    private CapturingInventoryPort inventory;
    private StockCountService service;

    @BeforeEach
    void setUp() {
        repository = new FakeStockCountRepository();
        inventory = new CapturingInventoryPort();
        service = new StockCountService(repository, inventory, NoopPublisher.INSTANCE);
    }

    // ---------------------------------------------------------------
    // 建单 + 差异计算
    // ---------------------------------------------------------------

    @Test
    void 建单为草稿_行号自增_账面快照保留_差异未录入时为null() {
        StockCountDocument doc = service.create("SC-202606-0001", WH, "月末盘点",
                List.of(line(P_A, "100"), line(P_B, "50")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, doc.getStatus());
        assertEquals(2, doc.getLines().size());
        assertEquals(1, doc.getLines().get(0).getLineNo());
        assertEquals(2, doc.getLines().get(1).getLineNo());
        assertEqualsDecimal("100", doc.getLines().get(0).getSnapshotQty());
        assertNull(doc.getLines().get(0).diffQty());
        assertTrue(repository.findByDocNo("SC-202606-0001").isPresent());
    }

    @Test
    void 建单空行拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("SC-1", WH, null, List.of(), OPERATOR));
    }

    @Test
    void 录入实盘后差异_盘盈为正_盘亏为负_无差异为零() {
        service.create("SC-1", WH, null,
                List.of(line(P_A, "100"), line(P_B, "50"), lineWithCost(P_C, "0", "8.00")), OPERATOR);

        service.enterCount("SC-1", 1, new BigDecimal("103"), OPERATOR); // 盘盈 +3
        service.enterCount("SC-1", 2, new BigDecimal("47"), OPERATOR);  // 盘亏 -3
        StockCountDocument doc = service.enterCount("SC-1", 3, new BigDecimal("0"), OPERATOR); // 无差异

        assertEqualsDecimal("3", doc.getLines().get(0).diffQty());
        assertEqualsDecimal("-3", doc.getLines().get(1).diffQty());
        assertEqualsDecimal("0", doc.getLines().get(2).diffQty());
    }

    @Test
    void 实盘数量为负拒绝() {
        service.create("SC-1", WH, null, List.of(line(P_A, "10")), OPERATOR);
        assertThrows(IllegalArgumentException.class,
                () -> service.enterCount("SC-1", 1, new BigDecimal("-1"), OPERATOR));
    }

    @Test
    void 录入不存在的行号拒绝() {
        service.create("SC-1", WH, null, List.of(line(P_A, "10")), OPERATOR);
        assertThrows(IllegalArgumentException.class,
                () -> service.enterCount("SC-1", 9, new BigDecimal("1"), OPERATOR));
    }

    // ---------------------------------------------------------------
    // 状态机全路径
    // ---------------------------------------------------------------

    @Test
    void 状态机全路径_草稿到审核到执行到完成() {
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("100"), OPERATOR); // 无差异

        assertEquals(DocumentStatus.APPROVED, service.approve("SC-1", OPERATOR).getStatus());
        // 无差异行过账：不产生流水，仅推进状态到 COMPLETED
        StockCountDocument completed = service.post("SC-1", OPERATOR);
        assertEquals(DocumentStatus.COMPLETED, completed.getStatus());
        assertTrue(inventory.executedBatches.isEmpty());
    }

    @Test
    void 审核前必须每行已录入实盘() {
        service.create("SC-1", WH, null, List.of(line(P_A, "100"), line(P_B, "50")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("100"), OPERATOR); // 第 2 行未录入

        assertThrows(IllegalArgumentException.class, () -> service.approve("SC-1", OPERATOR));
    }

    @Test
    void 非草稿不允许再录入实盘() {
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("100"), OPERATOR);
        service.approve("SC-1", OPERATOR);

        assertThrows(IllegalStateException.class,
                () -> service.enterCount("SC-1", 1, new BigDecimal("101"), OPERATOR));
    }

    @Test
    void 未审核直接过账非法流转() {
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("100"), OPERATOR);
        // DRAFT 直接 post：startExecution 走 DRAFT->EXECUTING 非法
        assertThrows(IllegalStateTransitionException.class, () -> service.post("SC-1", OPERATOR));
    }

    @Test
    void 已完成单据再过账非法流转() {
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("100"), OPERATOR);
        service.approve("SC-1", OPERATOR);
        service.post("SC-1", OPERATOR);

        assertThrows(IllegalStateTransitionException.class, () -> service.post("SC-1", OPERATOR));
    }

    // ---------------------------------------------------------------
    // 过账口径（拆解 §1.6.1）
    // ---------------------------------------------------------------

    @Test
    void 盘盈入库_有存量时按当前派生加权单价_盘亏出库由库存服务定价() {
        // 账面 P_A=100、P_B=50；P_A 盘盈到 110（+10），P_B 盘亏到 45（-5）
        inventory.setBalance(WH, P_A, "100", "1200.00"); // 派生单价 12.000000
        inventory.setBalance(WH, P_B, "50", "500.00");

        service.create("SC-1", WH, null, List.of(line(P_A, "100"), line(P_B, "50")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("110"), OPERATOR);
        service.enterCount("SC-1", 2, new BigDecimal("45"), OPERATOR);
        service.approve("SC-1", OPERATOR);
        service.post("SC-1", OPERATOR);

        List<StockMovementCommand> batch = inventory.lastBatch();
        assertEquals(2, batch.size());

        InboundCommand gain = (InboundCommand) batch.get(0);
        assertEquals(InventoryTxnType.COUNT_GAIN, gain.txnType());
        assertEquals(WH, gain.warehouseId());
        assertEquals(P_A, gain.productId());
        assertEqualsDecimal("10", gain.quantity());
        // 有存量盘盈单价 = 当前派生加权单价 1200.00/100 = 12.000000
        assertEqualsDecimal("12.000000", gain.unitCost());
        assertEquals("STOCK_COUNT", gain.srcDocType());
        assertEquals("SC-1", gain.srcDocNo());
        assertEquals(1, gain.srcLineNo());
        assertEquals("STOCK_COUNT:SC-1:1", gain.idempotencyKey());

        OutboundCommand loss = (OutboundCommand) batch.get(1);
        assertEquals(InventoryTxnType.COUNT_LOSS, loss.txnType());
        assertEquals(P_B, loss.productId());
        assertEqualsDecimal("5", loss.quantity());
        assertEquals("STOCK_COUNT:SC-1:2", loss.idempotencyKey());
    }

    @Test
    void 零库存盘盈_必须录入单价_缺失则拒绝过账() {
        // 账面 0，盘盈到 20（+20），未提供 enteredUnitCost
        inventory.setBalance(WH, P_A, "0", "0.00");
        service.create("SC-1", WH, null, List.of(line(P_A, "0")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("20"), OPERATOR);
        service.approve("SC-1", OPERATOR);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.post("SC-1", OPERATOR));
        assertTrue(ex.getMessage().contains("录入盘盈单价"), ex.getMessage());
        // 过账失败：未发生任何库存批次
        assertTrue(inventory.executedBatches.isEmpty());
    }

    @Test
    void 零库存盘盈_提供录入单价_按录入单价入库() {
        inventory.setBalance(WH, P_A, "0", "0.00");
        service.create("SC-1", WH, null, List.of(lineWithCost(P_A, "0", "8.50")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("20"), OPERATOR);
        service.approve("SC-1", OPERATOR);
        service.post("SC-1", OPERATOR);

        InboundCommand gain = (InboundCommand) inventory.lastBatch().get(0);
        assertEquals(InventoryTxnType.COUNT_GAIN, gain.txnType());
        assertEqualsDecimal("20", gain.quantity());
        assertEqualsDecimal("8.50", gain.unitCost());
    }

    @Test
    void 全部行无差异_过账不产生流水仅推进状态() {
        inventory.setBalance(WH, P_A, "100", "1000.00");
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("100"), OPERATOR);
        service.approve("SC-1", OPERATOR);
        StockCountDocument doc = service.post("SC-1", OPERATOR);

        assertEquals(DocumentStatus.COMPLETED, doc.getStatus());
        assertTrue(inventory.executedBatches.isEmpty());
    }

    @Test
    void 过账批量原子_库存执行抛异常时整体冒泡() {
        inventory.setBalance(WH, P_A, "100", "1000.00");
        inventory.failOnExecute = true;
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("110"), OPERATOR); // 盘盈，会触发 execute
        service.approve("SC-1", OPERATOR);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.post("SC-1", OPERATOR));
        assertTrue(ex.getMessage().contains("模拟库存过账失败"), ex.getMessage());
    }

    @Test
    void 多行盘盈盘亏混合_一次性组成一个批次过账() {
        inventory.setBalance(WH, P_A, "100", "1000.00");
        inventory.setBalance(WH, P_B, "50", "500.00");
        inventory.setBalance(WH, P_C, "30", "300.00");
        service.create("SC-1", WH, null,
                List.of(line(P_A, "100"), line(P_B, "50"), line(P_C, "30")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("105"), OPERATOR); // +5
        service.enterCount("SC-1", 2, new BigDecimal("50"), OPERATOR);  // 0（跳过）
        service.enterCount("SC-1", 3, new BigDecimal("28"), OPERATOR);  // -2
        service.approve("SC-1", OPERATOR);
        service.post("SC-1", OPERATOR);

        // 无差异行不进批次：批次恰 2 条且只执行一次（原子一批）
        assertEquals(1, inventory.executedBatches.size());
        assertEquals(2, inventory.lastBatch().size());
    }

    // ---------------------------------------------------------------
    // 查询 / 冲销
    // ---------------------------------------------------------------

    @Test
    void 查询不存在的盘点单抛NotFound() {
        assertThrows(StockCountNotFoundException.class, () -> service.get("SC-NONE"));
    }

    @Test
    void 冲销非已过账盘点单被拒() {
        // M4-T07c：reverse 已落地——草稿/未过账单不可冲销（仅 COMPLETED 可冲销）
        service.create("SC-1", WH, null, List.of(line(P_A, "1")), OPERATOR);
        assertThrows(IllegalStateException.class, () -> service.reverse("SC-1", OPERATOR));
    }

    @Test
    void 冲销已过账盘点单_盘盈反向出库_盘亏反向入库_原单转REVERSED() {
        // 账面 P_A=100 盘盈到 110（+10）、P_B=50 盘亏到 45（-5）
        inventory.setBalance(WH, P_A, "100", "1200.00"); // 派生 12.000000
        inventory.setBalance(WH, P_B, "50", "500.00");   // 派生 10.000000
        service.create("SC-1", WH, null, List.of(line(P_A, "100"), line(P_B, "50")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("110"), OPERATOR);
        service.enterCount("SC-1", 2, new BigDecimal("45"), OPERATOR);
        service.approve("SC-1", OPERATOR);
        service.post("SC-1", OPERATOR);
        // 桩原盘盈/盘亏流水固化单价（按原行幂等键 STOCK_COUNT:SC-1:行号）
        inventory.setOriginalUnitCost("STOCK_COUNT:SC-1:1", "12.000000"); // 原盘盈单价
        inventory.setOriginalUnitCost("STOCK_COUNT:SC-1:2", "10.000000"); // 原盘亏成本

        StockCountDocument reversed = service.reverse("SC-1", OPERATOR);
        assertEquals(DocumentStatus.REVERSED, reversed.getStatus());

        assertEquals(2, inventory.executedBatches.size()); // 过账 + 反向
        List<StockMovementCommand> batch = inventory.lastBatch();
        assertEquals(2, batch.size());

        // ① 原盘盈（COUNT_GAIN +10）→ 反向 COUNT_LOSS 出库 10，overriddenUnitCost=原盘盈单价，幂等键 REVERSAL:...
        OutboundCommand loss = (OutboundCommand) batch.get(0);
        assertEquals(InventoryTxnType.COUNT_LOSS, loss.txnType());
        assertEquals(WH, loss.warehouseId());
        assertEquals(P_A, loss.productId());
        assertEqualsDecimal("10", loss.quantity());
        assertEqualsDecimal("12.000000", loss.overriddenUnitCost());
        assertEquals("REVERSAL:SC-1:1", loss.idempotencyKey());

        // ② 原盘亏（COUNT_LOSS -5）→ 反向 COUNT_GAIN 入库 5，单价=原盘亏成本，幂等键 REVERSAL:...
        InboundCommand gain = (InboundCommand) batch.get(1);
        assertEquals(InventoryTxnType.COUNT_GAIN, gain.txnType());
        assertEquals(P_B, gain.productId());
        assertEqualsDecimal("5", gain.quantity());
        assertEqualsDecimal("10.000000", gain.unitCost());
        assertEquals("REVERSAL:SC-1:2", gain.idempotencyKey());
    }

    @Test
    void 冲销全无差异盘点单_仅推状态不产生反向流水() {
        inventory.setBalance(WH, P_A, "100", "1000.00");
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("100"), OPERATOR); // 无差异
        service.approve("SC-1", OPERATOR);
        service.post("SC-1", OPERATOR); // 无流水

        StockCountDocument reversed = service.reverse("SC-1", OPERATOR);
        assertEquals(DocumentStatus.REVERSED, reversed.getStatus());
        // 全程零库存批次（过账与反向均无差异行）
        assertTrue(inventory.executedBatches.isEmpty());
    }

    @Test
    void 重复冲销已REVERSED盘点单被拒_幂等() {
        inventory.setBalance(WH, P_A, "100", "1200.00");
        service.create("SC-1", WH, null, List.of(line(P_A, "100")), OPERATOR);
        service.enterCount("SC-1", 1, new BigDecimal("110"), OPERATOR); // 盘盈
        service.approve("SC-1", OPERATOR);
        service.post("SC-1", OPERATOR);
        inventory.setOriginalUnitCost("STOCK_COUNT:SC-1:1", "12.000000");
        service.reverse("SC-1", OPERATOR);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.reverse("SC-1", OPERATOR));
        assertTrue(ex.getMessage().contains("已冲销"), ex.getMessage());
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static StockCountLineInput line(long productId, String snapshot) {
        return new StockCountLineInput(productId, new BigDecimal(snapshot), null);
    }

    private static StockCountLineInput lineWithCost(long productId, String snapshot, String unitCost) {
        return new StockCountLineInput(productId, new BigDecimal(snapshot), new BigDecimal(unitCost));
    }

    // ---------------------------------------------------------------
    // 替身
    // ---------------------------------------------------------------

    /** 无操作事件发布器 */
    private enum NoopPublisher implements DomainEventPublisher {
        INSTANCE;

        @Override
        public void publish(DomainEvent event) {
            // no-op
        }
    }

    /** 内存盘点单仓储：按单据号存整聚合 */
    private static final class FakeStockCountRepository implements StockCountRepository {

        private final Map<String, StockCountDocument> store = new HashMap<>();

        @Override
        public void save(StockCountDocument document) {
            store.put(document.getDocNo(), document);
        }

        @Override
        public Optional<StockCountDocument> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<StockCountDocument> search(StockCountQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }

    /** 捕获过账批次的库存端口（可桩当前余额、可注入 execute 失败、可桩原流水固化单价供红冲读回） */
    private static final class CapturingInventoryPort implements InventoryPostingPort {

        private final Map<String, InventoryBalanceView> balances = new HashMap<>();
        final List<List<StockMovementCommand>> executedBatches = new ArrayList<>();
        /** 按原流水幂等键桩固化单价（M4-T07c originalUnitCost 读回） */
        final Map<String, BigDecimal> originalUnitCosts = new HashMap<>();
        boolean failOnExecute;
        private final AtomicLong txnId = new AtomicLong();

        void setOriginalUnitCost(String idempotencyKey, String unitCost) {
            originalUnitCosts.put(idempotencyKey, new BigDecimal(unitCost));
        }

        @Override
        public BigDecimal originalUnitCost(String idempotencyKey) {
            BigDecimal cost = originalUnitCosts.get(idempotencyKey);
            if (cost == null) {
                throw new IllegalStateException("原流水缺失或无单价（幂等键 " + idempotencyKey + "）");
            }
            return cost;
        }

        void setBalance(long warehouseId, long productId, String quantity, String costAmount) {
            balances.put(key(warehouseId, productId), new InventoryBalanceView(warehouseId, productId,
                    new BigDecimal(quantity).setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING),
                    new BigDecimal(costAmount).setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING)));
        }

        @Override
        public InventoryBalanceView balanceOf(long warehouseId, long productId) {
            return balances.getOrDefault(key(warehouseId, productId),
                    InventoryBalanceView.empty(warehouseId, productId));
        }

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

        private static String key(long warehouseId, long productId) {
            return warehouseId + ":" + productId;
        }
    }
}
