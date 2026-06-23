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

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.CostAdjustCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * {@link ProductionCostSettlementService} 单元测试（M5-T06）——纯内存，无 Spring/DB/Mockito。
 *
 * <p>验证：工费归集 Σ(hours×rate) + 工序无 rate 兜底默认费率；约当法分摊（含尾差并入完工）；
 * 完工结转 COST_ADJUST adjustAmount=完工工费增量 + 幂等键；多次结转增量防重复（照 T05 P0 教训）；
 * WIP 完工程度 0/50/100%；料工费=0 跳过 COST_ADJUST；完工程度越界拒；状态机。
 */
class ProductionCostSettlementServiceTest {

    // ================================================================ Fake 仓储与端口

    static class FakeSettlementRepository implements ProductionCostSettlementRepository {
        final Map<String, ProductionCostSettlement> store = new HashMap<>();
        final AtomicLong idGen = new AtomicLong(1);

        @Override
        public void save(ProductionCostSettlement s) {
            if (s.getId() == null) s.assignId(idGen.getAndIncrement());
            store.put(s.getDocNo(), s);
        }

        @Override
        public Optional<ProductionCostSettlement> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<ProductionCostSettlement> search(ProductionCostSettlementQuery q) {
            List<ProductionCostSettlement> all = List.copyOf(store.values());
            return new PageResult<>(all, all.size(), q.page(), q.size());
        }

        @Override
        public BigDecimal sumTransferredLaborOverheadByWorkOrder(String woDocNo) {
            return store.values().stream()
                    .filter(s -> s.getStatus() == DocumentStatus.COMPLETED)
                    .flatMap(s -> s.getLines().stream())
                    .filter(l -> l.getWorkOrderDocNo().equals(woDocNo))
                    .map(ProductionCostSettlementLine::completedLaborOverhead)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public PriorCumulative priorCumulativeByWorkOrder(String woDocNo, String excludeDocNo) {
            BigDecimal m = BigDecimal.ZERO, la = BigDecimal.ZERO, o = BigDecimal.ZERO, c = BigDecimal.ZERO;
            for (ProductionCostSettlement s : store.values()) {
                if (s.getStatus() != DocumentStatus.COMPLETED || s.getDocNo().equals(excludeDocNo)) {
                    continue;
                }
                for (ProductionCostSettlementLine l : s.getLines()) {
                    if (l.getWorkOrderDocNo().equals(woDocNo)) {
                        m = m.add(l.getMaterialCost());
                        la = la.add(l.getLaborCost());
                        o = o.add(l.getOverheadCost());
                        c = c.add(l.getCompletedCost());
                    }
                }
            }
            return new PriorCumulative(m, la, o, c);
        }
    }

    static class FakeWorkOrderRepository implements WorkOrderRepository {
        final Map<String, WorkOrder> store = new HashMap<>();
        final AtomicLong idGen = new AtomicLong(1);

        void put(WorkOrder wo) {
            if (wo.getId() == null) wo.assignId(idGen.getAndIncrement());
            store.put(wo.getDocNo(), wo);
        }

        @Override public void save(WorkOrder wo) { put(wo); }
        @Override public Optional<WorkOrder> findByDocNo(String docNo) { return Optional.ofNullable(store.get(docNo)); }
        @Override public PageResult<WorkOrder> search(WorkOrderQuery q) {
            List<WorkOrder> all = List.copyOf(store.values());
            return new PageResult<>(all, all.size(), q.page(), q.size());
        }
    }

    static class FakeMaterialIssueRepository implements MaterialIssueRepository {
        final List<MaterialIssue> completedIssues = new ArrayList<>();
        @Override public void save(MaterialIssue mi) {}
        @Override public Optional<MaterialIssue> findByDocNo(String docNo) {
            return completedIssues.stream().filter(m -> m.getDocNo().equals(docNo)).findFirst();
        }
        @Override public PageResult<MaterialIssue> search(MaterialIssueQuery q) {
            List<MaterialIssue> matched = completedIssues.stream()
                    .filter(m -> q.workOrderDocNo() == null || m.getWorkOrderDocNo().equals(q.workOrderDocNo()))
                    .filter(m -> q.status() == null || m.getStatus() == q.status())
                    .toList();
            return new PageResult<>(matched, matched.size(), q.page(), q.size());
        }
    }

    static class FakeProductionReportRepository implements ProductionReportRepository {
        final List<ProductionReport> completedReports = new ArrayList<>();
        @Override public void save(ProductionReport pr) {}
        @Override public Optional<ProductionReport> findByDocNo(String docNo) {
            return completedReports.stream().filter(p -> p.getDocNo().equals(docNo)).findFirst();
        }
        @Override public PageResult<ProductionReport> search(ProductionReportQuery q) {
            List<ProductionReport> matched = completedReports.stream()
                    .filter(p -> q.workOrderDocNo() == null || p.getWorkOrderDocNo().equals(q.workOrderDocNo()))
                    .filter(p -> q.status() == null || p.getStatus() == q.status())
                    .toList();
            return new PageResult<>(matched, matched.size(), q.page(), q.size());
        }
        @Override public BigDecimal sumInboundCostByWorkOrder(String woDocNo) { return BigDecimal.ZERO; }
    }

    static class FakeRoutingRepository implements RoutingRepository {
        final Map<Long, Routing> activeByProduct = new HashMap<>();
        @Override public void save(Routing routing) {}
        @Override public Optional<Routing> findById(long id) { return Optional.empty(); }
        @Override public Optional<Routing> findByProductAndVersion(long pid, int v) { return Optional.empty(); }
        @Override public List<Routing> findEnabledByProductId(long pid) { return List.of(); }
        @Override public PageResult<Routing> search(RoutingQuery q) { return new PageResult<>(List.of(), 0, 1, 20); }
        @Override public Optional<Routing> findActiveByProductId(long pid) {
            return Optional.ofNullable(activeByProduct.get(pid));
        }
        @Override public boolean existsByProductAndVersion(long pid, int v) { return false; }
    }

    static class FakeCostParamRepository implements ProductionCostParamRepository {
        final Map<String, ProductionCostParam> byPeriod = new HashMap<>();
        @Override public Optional<ProductionCostParam> findByPeriod(String period) {
            return Optional.ofNullable(byPeriod.get(period));
        }
    }

    /** 捕获 COST_ADJUST 命令，回 totalCost=adjustAmount。 */
    static class FakeInventoryPostingPort implements InventoryPostingPort {
        final List<StockMovementCommand> captured = new ArrayList<>();
        @Override
        public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
            captured.addAll(batch);
            List<StockMovementResult> results = new ArrayList<>();
            for (StockMovementCommand cmd : batch) {
                if (cmd instanceof CostAdjustCommand adj) {
                    results.add(new StockMovementResult(1L, cmd.warehouseId(), cmd.productId(),
                            InventoryTxnType.COST_ADJUST, BigDecimal.ZERO, null, adj.adjustAmount(),
                            BigDecimal.ZERO, BigDecimal.ZERO,
                            cmd.srcDocType(), cmd.srcDocNo(), cmd.srcLineNo(), cmd.idempotencyKey()));
                }
            }
            return results;
        }

        List<CostAdjustCommand> costAdjusts() {
            return captured.stream().filter(c -> c instanceof CostAdjustCommand)
                    .map(c -> (CostAdjustCommand) c).toList();
        }
    }

    // ================================================================ 装配

    private static final long PRODUCT_ID = 100L;
    private static final long WAREHOUSE_ID = 9L;
    private static final String PERIOD = "202606";

    private FakeSettlementRepository repo;
    private FakeWorkOrderRepository woRepo;
    private FakeMaterialIssueRepository issueRepo;
    private FakeProductionReportRepository reportRepo;
    private FakeRoutingRepository routingRepo;
    private FakeCostParamRepository paramRepo;
    private FakeInventoryPostingPort inventory;
    private ProductionCostSettlementService service;
    private final DomainEventPublisher publisher = e -> {};

    @BeforeEach
    void setUp() {
        repo = new FakeSettlementRepository();
        woRepo = new FakeWorkOrderRepository();
        issueRepo = new FakeMaterialIssueRepository();
        reportRepo = new FakeProductionReportRepository();
        routingRepo = new FakeRoutingRepository();
        paramRepo = new FakeCostParamRepository();
        inventory = new FakeInventoryPostingPort();
        // 系统默认人工费率 20、制造费用率 5（账期无参数行时兜底）
        service = new ProductionCostSettlementService(repo, woRepo, issueRepo, reportRepo, routingRepo,
                paramRepo, inventory, publisher, new BigDecimal("20"), new BigDecimal("5"));
    }

    // ---------------------------------------------------------------- 辅助

    private WorkOrder buildWorkOrder(String docNo, BigDecimal completedQty, DocumentStatus targetStatus) {
        WorkOrder wo = WorkOrder.create(docNo, PRODUCT_ID, new BigDecimal("10"), 1L,
                null, null, WAREHOUSE_ID, null, null, null, "op");
        wo.registerEventPublisher(publisher);
        wo.release("op");
        wo.start("op");
        if (completedQty.signum() > 0) {
            wo.recordCompletion(completedQty, "op");
        }
        if (targetStatus == DocumentStatus.COMPLETED) {
            wo.complete("op");
        }
        woRepo.put(wo);
        return wo;
    }

    private void addCompletedIssue(String woDocNo, BigDecimal issuedCost) {
        MaterialIssueLine line = MaterialIssueLine.restore(
                1L, 1, 200L, new BigDecimal("5"), new BigDecimal("5"), 1L, issuedCost);
        MaterialIssue mi = MaterialIssue.restore(
                issueRepo.completedIssues.size() + 10L,
                "MI-" + (issueRepo.completedIssues.size() + 1),
                woDocNo, WAREHOUSE_ID, null, DocumentStatus.COMPLETED, null, null,
                List.of(line), "op", "op");
        issueRepo.completedIssues.add(mi);
    }

    /** 加一张 COMPLETED 报工单（含若干工时行），用于工费归集。 */
    private void addCompletedReport(String woDocNo, BigDecimal hours, Integer opSeqNo) {
        ProductionReportLine line = ProductionReportLine.restore(
                1L, 1, opSeqNo, "工序", null, hours, null, 1L);
        ProductionReport pr = ProductionReport.restore(
                reportRepo.completedReports.size() + 100L,
                "PR-" + (reportRepo.completedReports.size() + 1),
                woDocNo, WAREHOUSE_ID, PRODUCT_ID,
                new BigDecimal("5"), BigDecimal.ZERO, 1L, new BigDecimal("500.00"), null,
                DocumentStatus.COMPLETED, null, null, List.of(line), "op", "op");
        reportRepo.completedReports.add(pr);
    }

    // ---------------------------------------------------------------- 工费归集

    @Test
    void create_工费归集_工序无rate用默认费率_费按制造费用率() {
        buildWorkOrder("WO-1", new BigDecimal("10"), DocumentStatus.EXECUTING);
        addCompletedIssue("WO-1", new BigDecimal("300.00"));
        // 报工 10 工时，工序序号 1，无 active routing → 用默认人工费率 20
        addCompletedReport("WO-1", new BigDecimal("10"), 1);

        ProductionCostSettlement s = service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");

        ProductionCostSettlementLine line = s.getLines().get(0);
        assertThat(line.getMaterialCost()).isEqualByComparingTo("300.00");
        // 工 = 10 × 20 = 200；费 = 10 × 5 = 50
        assertThat(line.getLaborCost()).isEqualByComparingTo("200.00");
        assertThat(line.getOverheadCost()).isEqualByComparingTo("50.00");
        // 全部完工（在产 0）→ 完工工费 = 250；完工成本 = 料 300 + 工费 250 = 550
        assertThat(line.getCompletedCost()).isEqualByComparingTo("550.00");
        assertThat(line.getWipCost()).isEqualByComparingTo("0.00");
    }

    @Test
    void create_工序有costRate_优先用工序费率() {
        WorkOrder wo = buildWorkOrder("WO-1", new BigDecimal("10"), DocumentStatus.EXECUTING);
        addCompletedIssue("WO-1", new BigDecimal("0.00"));
        addCompletedReport("WO-1", new BigDecimal("10"), 1);
        // active routing 工序 1 费率 30（覆盖默认 20）
        Routing routing = new Routing(wo.getProductId(), 1, null,
                List.of(new RoutingOperation(1, "工序", new BigDecimal("1"), null, new BigDecimal("30"))), "op");
        routingRepo.activeByProduct.put(wo.getProductId(), routing);

        ProductionCostSettlement s = service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");

        // 工 = 10 × 30 = 300
        assertThat(s.getLines().get(0).getLaborCost()).isEqualByComparingTo("300.00");
    }

    // ---------------------------------------------------------------- 完工结转 COST_ADJUST

    @Test
    void post_完工结转_COST_ADJUST金额为完工工费增量_幂等键正确() {
        buildWorkOrder("WO-1", new BigDecimal("10"), DocumentStatus.COMPLETED);
        addCompletedIssue("WO-1", new BigDecimal("300.00"));
        addCompletedReport("WO-1", new BigDecimal("10"), 1); // 工 200 + 费 50 = 250

        service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        service.approve("PC-1", "op");
        ProductionCostSettlement posted = service.post("PC-1", "op");

        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        // 完工工费 = completedCost 550 − material 300 = 250；alreadyTransferred 0 → 增量 250
        List<CostAdjustCommand> adjusts = inventory.costAdjusts();
        assertThat(adjusts).hasSize(1);
        assertThat(adjusts.get(0).adjustAmount()).isEqualByComparingTo("250.00");
        assertThat(adjusts.get(0).warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(adjusts.get(0).productId()).isEqualTo(PRODUCT_ID);
        assertThat(adjusts.get(0).idempotencyKey())
                .isEqualTo("PRODUCTION_COST_SETTLEMENT:PC-1:1");
        assertThat(posted.getLines().get(0).getCostAdjustIdemKey())
                .isEqualTo("PRODUCTION_COST_SETTLEMENT:PC-1:1");
    }

    @Test
    void post_多次结转_增量防重复入账() {
        // 第一次：料 300、工费 250 → 完工工费 250 入账
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        addCompletedIssue("WO-1", new BigDecimal("300.00"));
        addCompletedReport("WO-1", new BigDecimal("10"), 1); // 工 200+费 50 = 250
        service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        service.approve("PC-1", "op");
        service.post("PC-1", "op");

        assertThat(inventory.costAdjusts()).hasSize(1);
        assertThat(inventory.costAdjusts().get(0).adjustAmount()).isEqualByComparingTo("250.00");

        // 第二次：再领料 100、再报工 5 工时（工 100+费 25=125）。累计工费 = 250+125 = 375
        addCompletedIssue("WO-1", new BigDecimal("100.00"));   // 累计料 400
        addCompletedReport("WO-1", new BigDecimal("5"), 1);    // 累计报工 15 工时
        service.create("PC-2", "202607", null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        service.approve("PC-2", "op");
        service.post("PC-2", "op");

        // 第二次完工工费累计 = 375，alreadyTransferred = 250（PC-1 已结转）→ 增量 = 125
        List<CostAdjustCommand> adjusts = inventory.costAdjusts();
        assertThat(adjusts).hasSize(2);
        assertThat(adjusts.get(1).adjustAmount()).isEqualByComparingTo("125.00");
        // Σ增量 (250+125=375) == 累计完工工费，无重复入账
        assertThat(adjusts.get(0).adjustAmount().add(adjusts.get(1).adjustAmount()))
                .isEqualByComparingTo("375.00");
    }

    @Test
    void post_完工工费增量为0_跳过COST_ADJUST_不报错() {
        // 第一次全额结转完工工费 250
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        addCompletedIssue("WO-1", new BigDecimal("300.00"));
        addCompletedReport("WO-1", new BigDecimal("10"), 1);
        service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        service.approve("PC-1", "op");
        service.post("PC-1", "op");
        int afterFirst = inventory.costAdjusts().size();

        // 第二次无新增领料/报工 → 累计工费仍 250，alreadyTransferred 250 → 增量 0 → 跳过 COST_ADJUST
        service.create("PC-2", "202607", null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        service.approve("PC-2", "op");
        ProductionCostSettlement posted = service.post("PC-2", "op");

        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED); // 不报错
        assertThat(inventory.costAdjusts()).hasSize(afterFirst); // 无新增 COST_ADJUST
    }

    @Test
    void post_料工费全0_跳过COST_ADJUST() {
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        // 无领料、无报工 → 料 0 工 0 费 0
        service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        service.approve("PC-1", "op");
        ProductionCostSettlement posted = service.post("PC-1", "op");

        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(inventory.costAdjusts()).isEmpty();
    }

    // ---------------------------------------------------------------- WIP 完工程度

    @Test
    void create_在产50pct_工费部分留WIP() {
        // 完工 5、在产 5 @50%。工费 = 工 200 + 费 50 = 250
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        addCompletedIssue("WO-1", new BigDecimal("300.00"));
        addCompletedReport("WO-1", new BigDecimal("10"), 1);

        ProductionCostSettlement s = service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1",
                        new BigDecimal("5"), new BigDecimal("50"))), "op");

        ProductionCostSettlementLine line = s.getLines().get(0);
        // 在产约当 = 5×0.5 = 2.5，总约当 = 5+2.5 = 7.5，单位 = 250/7.5 = 33.333333
        // 在产工费 = 2.5×33.333333 = 83.33；完工工费 = 250 − 83.33 = 166.67
        assertThat(line.getWipCost()).isEqualByComparingTo("83.33");
        // 完工成本 = 料 300 + 完工工费 166.67 = 466.67
        assertThat(line.getCompletedCost()).isEqualByComparingTo("466.67");
    }

    @Test
    void create_完工程度越界_拒绝() {
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        assertThatThrownBy(() -> service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1",
                        new BigDecimal("5"), new BigDecimal("150"))), "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完工程度");
    }

    @Test
    void create_在产数量为负_拒绝() {
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        assertThatThrownBy(() -> service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1",
                        new BigDecimal("-1"), new BigDecimal("50"))), "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("在产数量");
    }

    // ---------------------------------------------------------------- 校验与状态机

    @Test
    void create_工单不存在_抛WorkOrderNotFound() {
        assertThatThrownBy(() -> service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-NONE", BigDecimal.ZERO, BigDecimal.ZERO)), "op"))
                .isInstanceOf(WorkOrderNotFoundException.class);
    }

    @Test
    void create_工单状态非EXECUTING或COMPLETED_拒绝() {
        WorkOrder wo = WorkOrder.create("WO-1", PRODUCT_ID, new BigDecimal("10"), 1L,
                null, null, WAREHOUSE_ID, null, null, null, "op");
        woRepo.put(wo); // DRAFT
        assertThatThrownBy(() -> service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可成本结转");
    }

    @Test
    void create_空行_拒绝() {
        assertThatThrownBy(() -> service.create("PC-1", PERIOD, null, List.of(), "op"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_DRAFT到APPROVED() {
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        addCompletedIssue("WO-1", new BigDecimal("100.00"));
        service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        assertThat(service.approve("PC-1", "op").getStatus()).isEqualTo(DocumentStatus.APPROVED);
    }

    @Test
    void cancel_仅DRAFT可作废() {
        buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        addCompletedIssue("WO-1", new BigDecimal("100.00"));
        service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        assertThat(service.cancel("PC-1", "op").getStatus()).isEqualTo(DocumentStatus.CANCELLED);
    }

    @Test
    void get_不存在_抛NotFound() {
        assertThatThrownBy(() -> service.get("PC-NONE"))
                .isInstanceOf(ProductionCostSettlementNotFoundException.class);
    }

    @Test
    void create_未启用routing也用默认费率_不影响() {
        WorkOrder wo = buildWorkOrder("WO-1", new BigDecimal("5"), DocumentStatus.EXECUTING);
        addCompletedReport("WO-1", new BigDecimal("4"), 1);
        // 放一个 DISABLED routing：findActiveByProductId 返回空 → 用默认费率 20
        Routing disabled = new Routing(wo.getProductId(), 1, null,
                List.of(new RoutingOperation(1, "工序", new BigDecimal("1"), null, new BigDecimal("99"))), "op");
        disabled.disable("op");
        // activeByProduct 不放 → findActiveByProductId 返回空
        ProductionCostSettlement s = service.create("PC-1", PERIOD, null,
                List.of(new ProductionCostSettlementLineInput("WO-1", BigDecimal.ZERO, BigDecimal.ZERO)), "op");
        // 工 = 4 × 20（默认）= 80
        assertThat(s.getLines().get(0).getLaborCost()).isEqualByComparingTo("80.00");
        assertThat(disabled.getStatus()).isEqualTo(ArchiveStatus.DISABLED);
    }
}
