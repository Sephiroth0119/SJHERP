package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 报工单领域服务（M5-T05）。
 *
 * <p>所有报工单写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零 Spring 依赖。
 *
 * <p>过账编排（§3 设计真源）：
 * <ol>
 *   <li>加载报工单，校验关联工单存在且 EXECUTING</li>
 *   <li>计算单位成本 = Σ(工单所有已过账领料单 issuedCost) / completedQty；为零则拒绝（D3）</li>
 *   <li>pr.startExecution → InboundCommand(PRODUCTION_IN) → 库存唯一入口 → assignInboundCost</li>
 *   <li>workOrder.recordCompletion + save 工单</li>
 *   <li>pr.complete → save 报工单</li>
 * </ol>
 *
 * <p>调用方（app 层 ProductionReportAppService）须包外层 @Transactional 保原子性。
 * 本批不调 autoVoucherService（GL 留 T06）。
 */
public class ProductionReportService {

    /** 库存流水来源单据类型 */
    static final String SRC_DOC_TYPE = "PRODUCTION_REPORT";

    private final ProductionReportRepository repository;
    private final InventoryPostingPort inventory;
    private final WorkOrderRepository workOrderRepository;
    private final MaterialIssueRepository issueRepository;
    private final DomainEventPublisher eventPublisher;

    public ProductionReportService(ProductionReportRepository repository,
                                    InventoryPostingPort inventory,
                                    WorkOrderRepository workOrderRepository,
                                    MaterialIssueRepository issueRepository,
                                    DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.workOrderRepository = Objects.requireNonNull(workOrderRepository, "workOrderRepository 不能为空");
        this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建报工单（草稿）。
     *
     * <p>校验：工单须存在且为 EXECUTING；生产商品须与工单一致；完工量 > 0；至少一行工时。
     *
     * @param docNo          单号（PR- 前缀，app 层 DocumentNumberGenerator 生成）
     * @param workOrderDocNo 关联工单号
     * @param warehouseId    产成品入库仓库 id
     * @param productId      生产商品 id（须与工单 productId 一致）
     * @param completedQty   本次完工入库数量（> 0）
     * @param scrapQty       本次报废数量（≥ 0，可为 null 默认 0，不入库）
     * @param unitId         计量单位 id
     * @param remark         备注（可空）
     * @param lines          工时行（至少一行）
     * @param operator       创建人
     */
    @Audited(action = "production_report.create", targetType = "production_report")
    public ProductionReport create(String docNo, String workOrderDocNo, long warehouseId,
                                    long productId, BigDecimal completedQty, BigDecimal scrapQty,
                                    long unitId, String remark,
                                    List<ProductionReportLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(workOrderDocNo, "关联工单号不能为空");
        Objects.requireNonNull(lines, "工时行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("报工单至少要有一行工时记录");
        }

        // 工单须存在且为 EXECUTING
        WorkOrder wo = workOrderRepository.findByDocNo(workOrderDocNo)
                .orElseThrow(() -> new WorkOrderNotFoundException(workOrderDocNo));
        if (wo.getStatus() != DocumentStatus.EXECUTING) {
            throw new IllegalArgumentException("工单[" + workOrderDocNo + "] 当前状态 " + wo.getStatus()
                    + " 不是开工状态（EXECUTING），不可报工");
        }

        // 商品 id 须与工单一致
        if (wo.getProductId() != productId) {
            throw new IllegalArgumentException("报工单商品 id=" + productId
                    + " 与工单[" + workOrderDocNo + "] 商品 id=" + wo.getProductId() + " 不一致");
        }

        // 构建工时行
        List<ProductionReportLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (ProductionReportLineInput input : lines) {
            domainLines.add(ProductionReportLine.create(lineNo++, input.operationSeqNo(),
                    input.operationName(), input.workCenter(),
                    input.reportedHours(), input.reportedQty(), input.unitId()));
        }

        ProductionReport pr = ProductionReport.create(docNo, workOrderDocNo, warehouseId, productId,
                completedQty, scrapQty, unitId, remark, domainLines, operator);
        pr.registerEventPublisher(eventPublisher);
        repository.save(pr);
        return pr;
    }

    /** 审核报工单：DRAFT → APPROVED。 */
    @Audited(action = "production_report.approve", targetType = "production_report")
    public ProductionReport approve(String docNo, String operator) {
        requireOperator(operator);
        ProductionReport pr = get(docNo);
        pr.registerEventPublisher(eventPublisher);
        pr.approve(operator);
        repository.save(pr);
        return pr;
    }

    /** 作废报工单：仅 DRAFT 可作废。 */
    @Audited(action = "production_report.cancel", targetType = "production_report")
    public ProductionReport cancel(String docNo, String operator) {
        requireOperator(operator);
        ProductionReport pr = get(docNo);
        pr.registerEventPublisher(eventPublisher);
        pr.cancel(operator);
        repository.save(pr);
        return pr;
    }

    /**
     * 过账报工单：APPROVED → EXECUTING → COMPLETED，完工入库走库存唯一写入口。
     *
     * <p>过账编排（§3）：
     * <ol>
     *   <li>校验关联工单存在且 EXECUTING</li>
     *   <li>unitCost = Σ 工单已过账领料单 issuedCost / completedQty；为零拒绝（D3）</li>
     *   <li>pr.startExecution → PRODUCTION_IN 入库 → assignInboundCost</li>
     *   <li>workOrder.recordCompletion + save 工单</li>
     *   <li>pr.complete + save 报工单</li>
     * </ol>
     *
     * @param docNo    报工单号
     * @param operator 操作人
     */
    @Audited(action = "production_report.post", targetType = "production_report")
    public ProductionReport post(String docNo, String operator) {
        requireOperator(operator);
        ProductionReport pr = get(docNo);
        pr.registerEventPublisher(eventPublisher);

        // 1. 校验关联工单存在且 EXECUTING
        WorkOrder wo = workOrderRepository.findByDocNo(pr.getWorkOrderDocNo())
                .orElseThrow(() -> new WorkOrderNotFoundException(pr.getWorkOrderDocNo()));
        if (wo.getStatus() != DocumentStatus.EXECUTING) {
            throw new IllegalArgumentException("工单[" + pr.getWorkOrderDocNo() + "] 当前状态 "
                    + wo.getStatus() + " 不是开工状态（EXECUTING），不可完工入库");
        }

        // 2. 计算完工入库单位成本。
        //    本次应结转料费 = 工单全部已过账领料 issuedCost 之和 − 前序报工已结转料费（inbound_cost 累计）。
        //    这样 Σ完工入库金额 ≡ Σ领料出库金额（料的进出守恒，设计真源 R1），分批完工不重复计入同一批料费（评审 P0）。
        BigDecimal totalIssuedCost = sumIssuedCostForWorkOrder(pr.getWorkOrderDocNo());
        BigDecimal alreadyInboundCost = repository.sumInboundCostByWorkOrder(pr.getWorkOrderDocNo());
        BigDecimal incrementalCost = totalIssuedCost.subtract(alreadyInboundCost);
        if (incrementalCost.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("工单[" + pr.getWorkOrderDocNo()
                    + "] 无可结转的新增领料成本（已过账领料 issuedCost 合计=" + totalIssuedCost.toPlainString()
                    + "，前序报工已结转=" + alreadyInboundCost.toPlainString()
                    + "），拒绝零成本完工入库（D3 / 防重复入账 R1）");
        }
        BigDecimal unitCost = incrementalCost.divide(
                pr.getCompletedQty(), CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);

        // 3. pr APPROVED → EXECUTING
        pr.startExecution(operator);

        // 4. 完工入库：一条 PRODUCTION_IN InboundCommand
        List<StockMovementCommand> batch = List.of(new InboundCommand(
                pr.getWarehouseId(),
                pr.getProductId(),
                InventoryTxnType.PRODUCTION_IN,
                pr.getCompletedQty(),
                unitCost,
                null,                    // transferOutKey（不适用）
                SRC_DOC_TYPE,
                pr.getDocNo(),
                1,                       // lineNo（单行入库固定 1）
                idempotencyKey(pr.getDocNo(), 1)));

        List<StockMovementResult> results = inventory.execute(batch, operator);
        if (results.isEmpty()) {
            throw new IllegalStateException("报工单[" + pr.getDocNo() + "] 完工入库未返回结果");
        }
        StockMovementResult result = results.get(0);

        // 5. 回填入库成本（totalCost 入库为正）
        pr.assignInboundCost(result.totalCost());

        // 6. 回写工单累计完工量
        wo.recordCompletion(pr.getCompletedQty(), operator);
        workOrderRepository.save(wo);

        // 7. pr EXECUTING → COMPLETED
        pr.complete(operator);
        repository.save(pr);
        return pr;
    }

    /** 按单号查询（不存在抛 ProductionReportNotFoundException → 404） */
    public ProductionReport get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new ProductionReportNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<ProductionReport> search(ProductionReportQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    /**
     * 汇总该工单所有已过账领料单的 issuedCost 合计（料费汇总口径：取 COMPLETED 状态领料单各行 issuedCost 之和）。
     */
    private BigDecimal sumIssuedCostForWorkOrder(String workOrderDocNo) {
        // 取该工单关联的所有 COMPLETED 领料单，累计各行 issuedCost
        // 使用 search 按工单号查全量（大工单领料单不会太多，小企业场景）
        int page = 1;  // MaterialIssueQuery 页码从 1 起
        BigDecimal total = BigDecimal.ZERO;
        while (true) {
            var result = issueRepository.search(new MaterialIssueQuery(workOrderDocNo,
                    DocumentStatus.COMPLETED, page, 200));
            for (MaterialIssue mi : result.items()) {
                total = total.add(mi.totalIssuedCost());
            }
            if (result.items().size() < 200) {
                break;
            }
            page++;
        }
        return total.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    /** 幂等键：PRODUCTION_REPORT:docNo:行号 */
    static String idempotencyKey(String docNo, int lineNo) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
