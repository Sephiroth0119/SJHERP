package com.sjherp.domain.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * MaterialIssueService 单元测试（M5-T04）——纯内存，无 Spring，无 DB，无 Mockito。
 *
 * <p>验证：建单校验、工单状态校验、审核流转、过账状态+issuedCost 回填、
 * 作废仅 DRAFT 可用、post 后状态 COMPLETED。
 */
class MaterialIssueServiceTest {

    // ================================================================ Fake 仓储与端口

    static class FakeMaterialIssueRepository implements MaterialIssueRepository {
        final Map<String, MaterialIssue> store = new HashMap<>();
        final AtomicLong idGen = new AtomicLong(1);

        @Override
        public void save(MaterialIssue mi) {
            store.put(mi.getDocNo(), mi);
        }

        @Override
        public Optional<MaterialIssue> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<MaterialIssue> search(MaterialIssueQuery query) {
            List<MaterialIssue> all = List.copyOf(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }
    }

    static class FakeWorkOrderRepository implements WorkOrderRepository {
        final Map<String, WorkOrder> store = new HashMap<>();
        final AtomicLong idGen = new AtomicLong(1);

        void add(WorkOrder wo) {
            if (wo.getId() == null) wo.assignId(idGen.getAndIncrement());
            store.put(wo.getDocNo(), wo);
        }

        @Override
        public void save(WorkOrder wo) {
            if (wo.getId() == null) wo.assignId(idGen.getAndIncrement());
            store.put(wo.getDocNo(), wo);
        }

        @Override
        public Optional<WorkOrder> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<WorkOrder> search(WorkOrderQuery query) {
            List<WorkOrder> all = List.copyOf(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }
    }

    /**
     * Fake 库存过账端口：每条命令回报 totalCost = -quantity × unitPrice（出库为负）。
     * unitPrice 固定 30.00，测试用例中 totalCost = -qty × 30。
     */
    static class FakeInventoryPostingPort implements InventoryPostingPort {
        static final BigDecimal UNIT_PRICE = new BigDecimal("30.00");
        final List<StockMovementCommand> captured = new ArrayList<>();

        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            captured.addAll(batch);
            List<StockMovementResult> results = new ArrayList<>();
            for (StockMovementCommand cmd : batch) {
                // OutboundCommand 用于领料，quantity() 是 record 组件
                BigDecimal qty = switch (cmd) {
                    case com.sjherp.domain.inventory.OutboundCommand oc -> oc.quantity();
                    case com.sjherp.domain.inventory.InboundCommand ic -> ic.quantity();
                    default -> BigDecimal.ONE;
                };
                // 出库 totalCost 为负
                BigDecimal totalCost = qty.negate().multiply(UNIT_PRICE);
                results.add(new StockMovementResult(
                        1L, cmd.warehouseId(), cmd.productId(),
                        InventoryTxnType.PRODUCTION_ISSUE, qty.negate(),
                        UNIT_PRICE, totalCost,
                        BigDecimal.ZERO, BigDecimal.ZERO,
                        cmd.srcDocType(), cmd.srcDocNo(), cmd.srcLineNo(), cmd.idempotencyKey()));
            }
            return results;
        }
    }

    // ================================================================ 被测服务

    private FakeMaterialIssueRepository miRepo;
    private FakeWorkOrderRepository woRepo;
    private FakeInventoryPostingPort inventoryPort;
    private MaterialIssueService service;
    private DomainEventPublisher eventPublisher = event -> {};

    @BeforeEach
    void setUp() {
        miRepo = new FakeMaterialIssueRepository();
        woRepo = new FakeWorkOrderRepository();
        inventoryPort = new FakeInventoryPostingPort();
        service = new MaterialIssueService(miRepo, woRepo, inventoryPort, eventPublisher);
    }

    // ---------------------------------------------------------------- 辅助：创建并开工工单

    private WorkOrder buildExecutingWorkOrder(String docNo, long productId) {
        WorkOrder wo = WorkOrder.create(docNo, productId, new BigDecimal("10"), 1L,
                null, null, null, null, null, null, "operator");
        wo.assignId(1L);
        wo.registerEventPublisher(eventPublisher);
        wo.release("operator");   // DRAFT → APPROVED
        wo.start("operator");     // APPROVED → EXECUTING
        woRepo.store.put(docNo, wo);
        return wo;
    }

    // ---------------------------------------------------------------- 建单校验

    @Test
    void create_工单不存在_抛异常() {
        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("5"), new BigDecimal("4"), 1L));

        assertThatThrownBy(() -> service.create("MI-001", "WO-NOTEXIST", 1L, null, lines, "op"))
                .isInstanceOf(WorkOrderNotFoundException.class);
    }

    @Test
    void create_工单非EXECUTING状态_抛异常() {
        // 工单 DRAFT 状态，未开工
        WorkOrder wo = WorkOrder.create("WO-001", 100L, new BigDecimal("10"), 1L,
                null, null, null, null, null, null, "op");
        wo.assignId(1L);
        woRepo.store.put("WO-001", wo);

        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("5"), new BigDecimal("4"), 1L));

        assertThatThrownBy(() -> service.create("MI-001", "WO-001", 1L, null, lines, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTING");
    }

    @Test
    void create_空行列表_抛异常() {
        buildExecutingWorkOrder("WO-001", 100L);

        assertThatThrownBy(() -> service.create("MI-001", "WO-001", 1L, null, List.of(), "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少要有一行");
    }

    // ---------------------------------------------------------------- 正常建单

    @Test
    void create_成功_状态为DRAFT() {
        buildExecutingWorkOrder("WO-001", 100L);
        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("5"), new BigDecimal("4"), 1L));

        MaterialIssue mi = service.create("MI-001", "WO-001", 1L, "测试备注", lines, "op");

        assertThat(mi.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(mi.getDocNo()).isEqualTo("MI-001");
        assertThat(mi.getLines()).hasSize(1);
        // 建单时 issuedCost 为 null
        assertThat(mi.getLines().get(0).getIssuedCost()).isNull();
        // 落库
        assertThat(miRepo.store).containsKey("MI-001");
    }

    // ---------------------------------------------------------------- 审核

    @Test
    void approve_从DRAFT到APPROVED() {
        buildExecutingWorkOrder("WO-001", 100L);
        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("5"), new BigDecimal("4"), 1L));
        service.create("MI-001", "WO-001", 1L, null, lines, "op");

        MaterialIssue approved = service.approve("MI-001", "op");

        assertThat(approved.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    // ---------------------------------------------------------------- 过账

    @Test
    void post_过账后状态COMPLETED_issuedCost回填() {
        buildExecutingWorkOrder("WO-001", 100L);
        // 实领 4 件，Fake 单价 30，issuedCost = 4 × 30 = 120
        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("5"), new BigDecimal("4"), 1L));
        service.create("MI-001", "WO-001", 1L, null, lines, "op");
        service.approve("MI-001", "op");

        MaterialIssue posted = service.post("MI-001", "op");

        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(posted.getLines().get(0).getIssuedCost())
                .isEqualByComparingTo("120.00");
        // 库存过账端口被调用一次
        assertThat(inventoryPort.captured).hasSize(1);
    }

    @Test
    void post_多行_每行issuedCost独立回填() {
        buildExecutingWorkOrder("WO-001", 100L);
        // 两行：3 件 + 2 件
        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("3"), new BigDecimal("3"), 1L),
                new MaterialIssueLineInput(202L, new BigDecimal("2"), new BigDecimal("2"), 1L));
        service.create("MI-001", "WO-001", 1L, null, lines, "op");
        service.approve("MI-001", "op");

        MaterialIssue posted = service.post("MI-001", "op");

        // 行 1：3 × 30 = 90，行 2：2 × 30 = 60
        assertThat(posted.getLines().get(0).getIssuedCost()).isEqualByComparingTo("90.00");
        assertThat(posted.getLines().get(1).getIssuedCost()).isEqualByComparingTo("60.00");
    }

    // ---------------------------------------------------------------- 作废

    @Test
    void cancel_仅DRAFT可作废() {
        buildExecutingWorkOrder("WO-001", 100L);
        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("5"), new BigDecimal("4"), 1L));
        service.create("MI-001", "WO-001", 1L, null, lines, "op");

        MaterialIssue cancelled = service.cancel("MI-001", "op");

        assertThat(cancelled.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void cancel_APPROVED状态_抛状态转换异常() {
        buildExecutingWorkOrder("WO-001", 100L);
        List<MaterialIssueLineInput> lines = List.of(
                new MaterialIssueLineInput(201L, new BigDecimal("5"), new BigDecimal("4"), 1L));
        service.create("MI-001", "WO-001", 1L, null, lines, "op");
        service.approve("MI-001", "op");

        assertThatThrownBy(() -> service.cancel("MI-001", "op"))
                .isInstanceOf(Exception.class);
    }

    // ---------------------------------------------------------------- 查询

    @Test
    void get_不存在_抛MaterialIssueNotFoundException() {
        assertThatThrownBy(() -> service.get("MI-NOTEXIST"))
                .isInstanceOf(MaterialIssueNotFoundException.class);
    }
}
