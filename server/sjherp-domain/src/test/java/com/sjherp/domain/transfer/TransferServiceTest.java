package com.sjherp.domain.transfer;

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
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 调拨单领域服务单测（M3-T04）：状态机全路径、同仓拒绝、两腿构造（调出在前、调入在后）、
 * transferOutKey 关联（调入腿幂等键关联调出腿幂等键）、幂等键与来源单据约定、批量原子。
 * 用内存替身仓储 + 捕获库存端口，不依赖 Spring/DB。
 */
class TransferServiceTest {

    private static final long WH_FROM = 1L;
    private static final long WH_TO = 2L;
    private static final long P_A = 100L;
    private static final long P_B = 200L;
    private static final String OPERATOR = "tester";

    private FakeTransferRepository repository;
    private CapturingInventoryPort inventory;
    private TransferService service;

    @BeforeEach
    void setUp() {
        repository = new FakeTransferRepository();
        inventory = new CapturingInventoryPort();
        service = new TransferService(repository, inventory, NoopPublisher.INSTANCE);
    }

    // ---------------------------------------------------------------
    // 建单 + 基本校验
    // ---------------------------------------------------------------

    @Test
    void 建单为草稿_行号自增_数量保留() {
        TransferDocument doc = service.create("TR-202606-0001", WH_FROM, WH_TO, "门店补货",
                List.of(line(P_A, "100"), line(P_B, "50")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, doc.getStatus());
        assertEquals(WH_FROM, doc.getFromWarehouseId());
        assertEquals(WH_TO, doc.getToWarehouseId());
        assertEquals(2, doc.getLines().size());
        assertEquals(1, doc.getLines().get(0).getLineNo());
        assertEquals(2, doc.getLines().get(1).getLineNo());
        assertEqualsDecimal("100", doc.getLines().get(0).getQuantity());
        assertTrue(repository.findByDocNo("TR-202606-0001").isPresent());
    }

    @Test
    void 同仓调拨拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("TR-1", WH_FROM, WH_FROM, null, List.of(line(P_A, "10")), OPERATOR));
    }

    @Test
    void 建单空行拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("TR-1", WH_FROM, WH_TO, null, List.of(), OPERATOR));
    }

    @Test
    void 数量为零或负拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "0")), OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("TR-2", WH_FROM, WH_TO, null, List.of(line(P_A, "-5")), OPERATOR));
    }

    // ---------------------------------------------------------------
    // 状态机全路径
    // ---------------------------------------------------------------

    @Test
    void 状态机全路径_草稿到审核到执行到完成() {
        service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "100")), OPERATOR);
        assertEquals(DocumentStatus.APPROVED, service.approve("TR-1", OPERATOR).getStatus());
        TransferDocument completed = service.post("TR-1", OPERATOR);
        assertEquals(DocumentStatus.COMPLETED, completed.getStatus());
    }

    @Test
    void 未审核直接过账非法流转() {
        service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "100")), OPERATOR);
        // DRAFT 直接 post：startExecution 走 DRAFT->EXECUTING 非法
        assertThrows(IllegalStateTransitionException.class, () -> service.post("TR-1", OPERATOR));
    }

    @Test
    void 已完成单据再过账非法流转() {
        service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "100")), OPERATOR);
        service.approve("TR-1", OPERATOR);
        service.post("TR-1", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.post("TR-1", OPERATOR));
    }

    @Test
    void 重复审核非法流转() {
        service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "100")), OPERATOR);
        service.approve("TR-1", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.approve("TR-1", OPERATOR));
    }

    // ---------------------------------------------------------------
    // 过账两腿构造（拆解 §1.6.5，财务核心）
    // ---------------------------------------------------------------

    @Test
    void 过账每行拆成两腿_调出在前调入在后_一次原子批次() {
        service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "70")), OPERATOR);
        service.approve("TR-1", OPERATOR);
        service.post("TR-1", OPERATOR);

        // 一行 → 两腿，且只 execute 一次（原子一批）
        assertEquals(1, inventory.executedBatches.size());
        List<StockMovementCommand> batch = inventory.lastBatch();
        assertEquals(2, batch.size());

        // ① 调出腿（TRANSFER_OUT）从调出仓出库，幂等键 :OUT
        OutboundCommand out = (OutboundCommand) batch.get(0);
        assertEquals(InventoryTxnType.TRANSFER_OUT, out.txnType());
        assertEquals(WH_FROM, out.warehouseId());
        assertEquals(P_A, out.productId());
        assertEqualsDecimal("70", out.quantity());
        assertEquals("TRANSFER", out.srcDocType());
        assertEquals("TR-1", out.srcDocNo());
        assertEquals(1, out.srcLineNo());
        assertEquals("TRANSFER:TR-1:1:OUT", out.idempotencyKey());

        // ② 调入腿（TRANSFER_IN）入调入仓，幂等键 :IN，不指定单价
        InboundCommand in = (InboundCommand) batch.get(1);
        assertEquals(InventoryTxnType.TRANSFER_IN, in.txnType());
        assertEquals(WH_TO, in.warehouseId());
        assertEquals(P_A, in.productId());
        assertEqualsDecimal("70", in.quantity());
        assertNull(in.unitCost(), "调入腿不指定单价（成本取调出腿原值）");
        assertEquals("TRANSFER:TR-1:1:IN", in.idempotencyKey());
        // transferOutKey 关联调出腿幂等键（金额守恒机制）
        assertEquals("TRANSFER:TR-1:1:OUT", in.transferOutKey());
    }

    @Test
    void 多行调拨_每行两腿按行号顺序成对() {
        service.create("TR-1", WH_FROM, WH_TO, null,
                List.of(line(P_A, "10"), line(P_B, "20")), OPERATOR);
        service.approve("TR-1", OPERATOR);
        service.post("TR-1", OPERATOR);

        List<StockMovementCommand> batch = inventory.lastBatch();
        assertEquals(4, batch.size());
        // 行1 OUT/IN，行2 OUT/IN
        assertEquals("TRANSFER:TR-1:1:OUT", batch.get(0).idempotencyKey());
        assertEquals("TRANSFER:TR-1:1:IN", batch.get(1).idempotencyKey());
        assertEquals("TRANSFER:TR-1:2:OUT", batch.get(2).idempotencyKey());
        assertEquals("TRANSFER:TR-1:2:IN", batch.get(3).idempotencyKey());
        // 行2 调入腿 transferOutKey 关联行2 调出腿
        InboundCommand in2 = (InboundCommand) batch.get(3);
        assertEquals("TRANSFER:TR-1:2:OUT", in2.transferOutKey());
        assertEquals(P_B, in2.productId());
    }

    @Test
    void 过账批量原子_库存执行抛异常时整体冒泡() {
        inventory.failOnExecute = true;
        service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "10")), OPERATOR);
        service.approve("TR-1", OPERATOR);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.post("TR-1", OPERATOR));
        assertTrue(ex.getMessage().contains("模拟库存过账失败"), ex.getMessage());
    }

    // ---------------------------------------------------------------
    // 查询 / 冲销
    // ---------------------------------------------------------------

    @Test
    void 查询不存在的调拨单抛NotFound() {
        assertThrows(TransferNotFoundException.class, () -> service.get("TR-NONE"));
    }

    @Test
    void 冲销暂未实现_抛UnsupportedOperation() {
        service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "1")), OPERATOR);
        assertThrows(UnsupportedOperationException.class, () -> service.reverse("TR-1", OPERATOR));
    }

    @Test
    void operator为空拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("TR-1", WH_FROM, WH_TO, null, List.of(line(P_A, "1")), " "));
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static TransferLineInput line(long productId, String quantity) {
        return new TransferLineInput(productId, new BigDecimal(quantity));
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

    /** 内存调拨单仓储：按单据号存整聚合 */
    private static final class FakeTransferRepository implements TransferRepository {

        private final Map<String, TransferDocument> store = new HashMap<>();

        @Override
        public void save(TransferDocument document) {
            store.put(document.getDocNo(), document);
        }

        @Override
        public Optional<TransferDocument> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<TransferDocument> search(TransferQuery query) {
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
