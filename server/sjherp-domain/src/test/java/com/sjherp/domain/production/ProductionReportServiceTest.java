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
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * ProductionReportService 单元测试（M5-T05）——纯内存，无 Spring，无 DB，无 Mockito。
 *
 * <p>验证：建单校验（工单须 EXECUTING、productId 匹配、至少一行）、
 * 过账流程（成本汇总→入库→assignInboundCost→workOrder.recordCompletion→COMPLETED）、
 * 零成本拒绝（D3）、多次过账累加 completedQty（D5）、作废仅 DRAFT 可用。
 */
class ProductionReportServiceTest {

    // ================================================================ Fake 仓储与端口

    static class FakeProductionReportRepository implements ProductionReportRepository {
        final Map<String, ProductionReport> store = new HashMap<>();
        final AtomicLong idGen = new AtomicLong(1);

        @Override
        public void save(ProductionReport pr) {
            if (pr.getId() == null) pr.assignId(idGen.getAndIncrement());
            store.put(pr.getDocNo(), pr);
        }

        @Override
        public Optional<ProductionReport> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<ProductionReport> search(ProductionReportQuery query) {
            List<ProductionReport> all = List.copyOf(store.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public BigDecimal sumInboundCostByWorkOrder(String workOrderDocNo) {
            // 已结转料费锚点：仅累计 COMPLETED 报工单的 inboundCost（与 Jdbc 实现口径一致）
            return store.values().stream()
                    .filter(pr -> pr.getWorkOrderDocNo().equals(workOrderDocNo))
                    .filter(pr -> pr.getStatus() == DocumentStatus.COMPLETED)
                    .map(pr -> pr.getInboundCost() != null ? pr.getInboundCost() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    static class FakeWorkOrderRepository implements WorkOrderRepository {
        final Map<String, WorkOrder> store = new HashMap<>();
        final AtomicLong idGen = new AtomicLong(1);

        void put(WorkOrder wo) {
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

    static class FakeMaterialIssueRepository implements MaterialIssueRepository {
        /** 预设：该工单关联的已过账领料单（用于汇总成本） */
        final List<MaterialIssue> completedIssues = new ArrayList<>();

        @Override
        public void save(MaterialIssue mi) {}

        @Override
        public Optional<MaterialIssue> findByDocNo(String docNo) {
            return completedIssues.stream().filter(m -> m.getDocNo().equals(docNo)).findFirst();
        }

        @Override
        public PageResult<MaterialIssue> search(MaterialIssueQuery query) {
            // 仅返回与工单关联且 COMPLETED 的领料单
            List<MaterialIssue> matched = completedIssues.stream()
                    .filter(m -> query.workOrderDocNo() == null
                            || m.getWorkOrderDocNo().equals(query.workOrderDocNo()))
                    .filter(m -> query.status() == null || m.getStatus() == query.status())
                    .toList();
            return new PageResult<>(matched, matched.size(), query.page(), query.size());
        }
    }

    /**
     * Fake 库存过账端口：
     * - InboundCommand → totalCost = quantity × unitCost（正数，完工入库）
     * - OutboundCommand → totalCost = -quantity × unitPrice（负数，领料出库）
     */
    static class FakeInventoryPostingPort implements InventoryPostingPort {
        final List<StockMovementCommand> captured = new ArrayList<>();

        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            captured.addAll(batch);
            List<StockMovementResult> results = new ArrayList<>();
            for (StockMovementCommand cmd : batch) {
                BigDecimal qty;
                BigDecimal unitCost;
                BigDecimal totalCost;
                InventoryTxnType type;
                switch (cmd) {
                    case InboundCommand ic -> {
                        qty = ic.quantity();
                        unitCost = ic.unitCost();
                        totalCost = qty.multiply(unitCost).setScale(2, java.math.RoundingMode.HALF_UP);
                        type = ic.txnType();
                    }
                    case OutboundCommand oc -> {
                        qty = oc.quantity().negate();
                        unitCost = new BigDecimal("30.00");
                        totalCost = qty.multiply(unitCost).setScale(2, java.math.RoundingMode.HALF_UP);
                        type = oc.txnType();
                    }
                    default -> {
                        qty = BigDecimal.ONE;
                        unitCost = BigDecimal.ZERO;
                        totalCost = BigDecimal.ZERO;
                        type = InventoryTxnType.PRODUCTION_IN;
                    }
                }
                results.add(new StockMovementResult(
                        1L, cmd.warehouseId(), cmd.productId(),
                        type, qty,
                        unitCost, totalCost,
                        BigDecimal.ZERO, BigDecimal.ZERO,
                        cmd.srcDocType(), cmd.srcDocNo(), cmd.srcLineNo(), cmd.idempotencyKey()));
            }
            return results;
        }
    }

    // ================================================================ 被测服务

    private FakeProductionReportRepository prRepo;
    private FakeWorkOrderRepository woRepo;
    private FakeMaterialIssueRepository issueRepo;
    private FakeInventoryPostingPort inventoryPort;
    private ProductionReportService service;
    private final DomainEventPublisher eventPublisher = event -> {};

    @BeforeEach
    void setUp() {
        prRepo = new FakeProductionReportRepository();
        woRepo = new FakeWorkOrderRepository();
        issueRepo = new FakeMaterialIssueRepository();
        inventoryPort = new FakeInventoryPostingPort();
        service = new ProductionReportService(prRepo, inventoryPort, woRepo, issueRepo, eventPublisher);
    }

    // ---------------------------------------------------------------- 辅助：创建开工工单

    private static final long PRODUCT_ID = 100L;

    private WorkOrder buildExecutingWorkOrder(String docNo) {
        WorkOrder wo = WorkOrder.create(docNo, PRODUCT_ID, new BigDecimal("10"), 1L,
                null, null, null, null, null, null, "op");
        wo.registerEventPublisher(eventPublisher);
        wo.release("op");  // DRAFT → APPROVED
        wo.start("op");    // APPROVED → EXECUTING
        woRepo.put(wo);
        return wo;
    }

    /** 构建一个已过账的领料单，模拟工单已领料成本（供 sumIssuedCostForWorkOrder 汇总用）。 */
    private void addCompletedIssue(String woDocNo, BigDecimal issuedCostPerLine) {
        // 用 restore 直接构建 COMPLETED 状态的领料单（含 issuedCost 已填的行）
        MaterialIssueLine line = MaterialIssueLine.restore(
                1L, 1, 200L, new BigDecimal("5"), new BigDecimal("5"), 1L, issuedCostPerLine);
        MaterialIssue mi = MaterialIssue.restore(
                issueRepo.completedIssues.size() + 10L,
                "MI-00" + (issueRepo.completedIssues.size() + 1),
                woDocNo, 1L, null,
                DocumentStatus.COMPLETED, null, null,
                List.of(line), "op", "op");
        issueRepo.completedIssues.add(mi);
    }

    // ---------------------------------------------------------------- 建单校验

    @Test
    void create_工单不存在_抛WorkOrderNotFoundException() {
        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2.5"), null, 1L));

        assertThatThrownBy(() ->
                service.create("PR-001", "WO-NOTEXIST", 1L, PRODUCT_ID,
                        new BigDecimal("5"), null, 1L, null, lines, "op"))
                .isInstanceOf(WorkOrderNotFoundException.class);
    }

    @Test
    void create_工单非EXECUTING状态_抛异常() {
        WorkOrder wo = WorkOrder.create("WO-001", PRODUCT_ID, new BigDecimal("10"), 1L,
                null, null, null, null, null, null, "op");
        woRepo.put(wo);  // 保留 DRAFT 状态

        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2.5"), null, 1L));

        assertThatThrownBy(() ->
                service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                        new BigDecimal("5"), null, 1L, null, lines, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTING");
    }

    @Test
    void create_productId不匹配_抛异常() {
        buildExecutingWorkOrder("WO-001");

        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2.5"), null, 1L));

        // 工单 productId=100，报工单 productId=999
        assertThatThrownBy(() ->
                service.create("PR-001", "WO-001", 1L, 999L,
                        new BigDecimal("5"), null, 1L, null, lines, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void create_空工时行_抛异常() {
        buildExecutingWorkOrder("WO-001");

        assertThatThrownBy(() ->
                service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                        new BigDecimal("5"), null, 1L, null, List.of(), "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少");
    }

    // ---------------------------------------------------------------- 正常建单

    @Test
    void create_成功_状态DRAFT_行正确() {
        buildExecutingWorkOrder("WO-001");
        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "组装", "A线", new BigDecimal("3.0"), null, 1L));

        ProductionReport pr = service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("5"), new BigDecimal("0.5"), 1L, "备注", lines, "op");

        assertThat(pr.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(pr.getDocNo()).isEqualTo("PR-001");
        assertThat(pr.getLines()).hasSize(1);
        assertThat(pr.getCompletedQty()).isEqualByComparingTo("5");
        assertThat(pr.getScrapQty()).isEqualByComparingTo("0.5");
        assertThat(pr.getInboundCost()).isNull();
        assertThat(prRepo.store).containsKey("PR-001");
    }

    // ---------------------------------------------------------------- 审核

    @Test
    void approve_从DRAFT到APPROVED() {
        buildExecutingWorkOrder("WO-001");
        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2"), null, 1L));
        service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("5"), null, 1L, null, lines, "op");

        ProductionReport approved = service.approve("PR-001", "op");

        assertThat(approved.getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    // ---------------------------------------------------------------- 过账（核心路径）

    @Test
    void post_过账后状态COMPLETED_inboundCost回填_工单completedQty累加() {
        buildExecutingWorkOrder("WO-001");
        // 预设领料成本：1 张领料单，issuedCost = 150.00
        addCompletedIssue("WO-001", new BigDecimal("150.00"));

        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2.5"), null, 1L));
        service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("5"), null, 1L, null, lines, "op");
        service.approve("PR-001", "op");

        ProductionReport posted = service.post("PR-001", "op");

        // 状态 COMPLETED
        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        // inboundCost 回填：unitCost = 150/5 = 30，completedQty=5，totalCost = 5*30 = 150
        assertThat(posted.getInboundCost()).isNotNull();
        assertThat(posted.getInboundCost()).isEqualByComparingTo("150.00");
        // 库存入库被调用一次（PRODUCTION_IN）
        assertThat(inventoryPort.captured).hasSize(1);
        assertThat(inventoryPort.captured.get(0)).isInstanceOf(InboundCommand.class);
        // 工单 completedQty 增加 5
        WorkOrder wo = woRepo.store.get("WO-001");
        assertThat(wo.getCompletedQty()).isEqualByComparingTo("5");
    }

    @Test
    void post_零成本领料_D3_拒绝过账() {
        buildExecutingWorkOrder("WO-001");
        // 无任何已过账领料单（totalIssuedCost = 0）
        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2"), null, 1L));
        service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("5"), null, 1L, null, lines, "op");
        service.approve("PR-001", "op");

        assertThatThrownBy(() -> service.post("PR-001", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuedCost");
    }

    @Test
    void post_多次报工_D5_completedQty累加_料费不重复入账守恒() {
        buildExecutingWorkOrder("WO-001");

        List<ProductionReportLineInput> lines1 = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2"), null, 1L));
        List<ProductionReportLineInput> lines2 = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2"), null, 1L));

        // JIT：先领料 300（mi1）→ 报工 3 件
        addCompletedIssue("WO-001", new BigDecimal("300.00"));
        service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("3"), null, 1L, null, lines1, "op");
        service.approve("PR-001", "op");
        ProductionReport pr1 = service.post("PR-001", "op");

        // 再领料 200（mi2，工单累计领料 500）→ 报工 4 件（超过计划量 10，D5 允许超额）
        addCompletedIssue("WO-001", new BigDecimal("200.00"));
        service.create("PR-002", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("4"), null, 1L, null, lines2, "op");
        service.approve("PR-002", "op");
        ProductionReport pr2 = service.post("PR-002", "op");

        // completedQty 累加
        WorkOrder wo = woRepo.store.get("WO-001");
        assertThat(wo.getCompletedQty()).isEqualByComparingTo("7");  // 3 + 4

        // 评审 P0 守门：分批完工料费不重复入账——
        // pr1 入 300（已结转 0，增量 300），pr2 入 200（已结转 300，增量 500-300=200）
        assertThat(pr1.getInboundCost()).isEqualByComparingTo("300.00");
        assertThat(pr2.getInboundCost()).isEqualByComparingTo("200.00");
        // Σ完工入库金额 (300+200=500) ≡ Σ领料出库金额 (300+200=500)，料的进出守恒（R1）
        assertThat(pr1.getInboundCost().add(pr2.getInboundCost())).isEqualByComparingTo("500.00");
    }

    @Test
    void post_分批完工无新增领料_增量料费为0_拒绝过账() {
        // 评审 P0/D3：mi 一次领料 300 已被 pr1 全额结转，pr2 无新增领料 → 增量=0 → 拒绝（防零成本入库稀释加权）
        buildExecutingWorkOrder("WO-001");
        addCompletedIssue("WO-001", new BigDecimal("300.00"));

        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2"), null, 1L));
        service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("3"), null, 1L, null, lines, "op");
        service.approve("PR-001", "op");
        service.post("PR-001", "op");  // 结转全部 300

        // 第二张报工：无新增领料，增量 = 300 - 300 = 0 → 拒绝
        service.create("PR-002", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("4"), null, 1L, null, lines, "op");
        service.approve("PR-002", "op");

        assertThatThrownBy(() -> service.post("PR-002", "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("新增领料成本");

        // pr2 仍停在 APPROVED，工单 completedQty 不前进（仍为 pr1 的 3）
        assertThat(prRepo.store.get("PR-002").getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(woRepo.store.get("WO-001").getCompletedQty()).isEqualByComparingTo("3");
    }

    // ---------------------------------------------------------------- 作废

    @Test
    void cancel_仅DRAFT可作废() {
        buildExecutingWorkOrder("WO-001");
        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2"), null, 1L));
        service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("5"), null, 1L, null, lines, "op");

        ProductionReport cancelled = service.cancel("PR-001", "op");

        assertThat(cancelled.getStatus()).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void cancel_APPROVED状态_抛异常() {
        buildExecutingWorkOrder("WO-001");
        List<ProductionReportLineInput> lines = List.of(
                new ProductionReportLineInput(1, "焊接", null, new BigDecimal("2"), null, 1L));
        service.create("PR-001", "WO-001", 1L, PRODUCT_ID,
                new BigDecimal("5"), null, 1L, null, lines, "op");
        service.approve("PR-001", "op");

        assertThatThrownBy(() -> service.cancel("PR-001", "op"))
                .isInstanceOf(Exception.class);
    }

    // ---------------------------------------------------------------- 查询

    @Test
    void get_不存在_抛ProductionReportNotFoundException() {
        assertThatThrownBy(() -> service.get("PR-NOTEXIST"))
                .isInstanceOf(ProductionReportNotFoundException.class);
    }
}
