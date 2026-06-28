package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.CostAdjustCommand;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.inventory.StockMovementCommand;

/**
 * 月末成本结转单领域服务（M5-T06，全项目最难财务点）。
 *
 * <p>所有成本结转写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零 Spring 依赖。
 *
 * <h2>建单（create，会计政策真源 ADR §2/§3）</h2>
 * 按调用方传入的工单清单逐行算：
 * <ul>
 *   <li><b>料</b>：Σ 该工单 COMPLETED 领料单 issuedCost（复用 T05 口径，T06 读不改 inboundCost）；</li>
 *   <li><b>工</b>：Σ 该工单所有 COMPLETED 报工行 reportedHours × 工序 costRate（工序无 rate 用默认人工费率）；</li>
 *   <li><b>费</b>：Σ reportedHours × 制造费用率（配置参数，元/工时单一标准）；</li>
 *   <li><b>分摊</b>：约当产量法（{@link #allocate}）——料 100% 随完工结转（在产不含料），工费按完工程度
 *       折算约当量分摊完工/在产，尾差并入完工（R-T06-5）。</li>
 * </ul>
 *
 * <h2>过账（post，ADR §4/§5）</h2>
 * 同一外层 @Transactional 内：①校验账期 OPEN（由 app 层凭证服务的 post 守卫兜底）；
 * ②每行 CostAdjustCommand 追加完工工费增量（= 完工应负担工费 − alreadyTransferred，照 T05 防重复）
 * 到产成品仓经库存唯一入口；③出 GL（料/工费归集 + 完工结转，由 app 层 ProductionCostVoucherService）；
 * ④回填 costAdjustIdemKey/voucherDocNo；⑤状态 APPROVED→EXECUTING→COMPLETED。
 *
 * <p>GL 出凭证由 app 层在 post 返回后于同事务内调用（领域层不依赖 GL），见 ProductionCostSettlementAppService。
 */
public class ProductionCostSettlementService {

    /** 库存流水来源单据类型（COST_ADJUST 幂等键前缀） */
    static final String SRC_DOC_TYPE = "PRODUCTION_COST_SETTLEMENT";

    private final ProductionCostSettlementRepository repository;
    private final WorkOrderRepository workOrderRepository;
    private final MaterialIssueRepository issueRepository;
    private final ProductionReportRepository reportRepository;
    private final RoutingRepository routingRepository;
    private final ProductionCostParamRepository costParamRepository;
    private final InventoryPostingPort inventory;
    private final DomainEventPublisher eventPublisher;

    /** 系统级默认人工费率/制造费用率（账期无 production_cost_param 行时兜底） */
    private final BigDecimal systemDefaultLaborRate;
    private final BigDecimal systemDefaultOverheadRate;

    public ProductionCostSettlementService(ProductionCostSettlementRepository repository,
                                           WorkOrderRepository workOrderRepository,
                                           MaterialIssueRepository issueRepository,
                                           ProductionReportRepository reportRepository,
                                           RoutingRepository routingRepository,
                                           ProductionCostParamRepository costParamRepository,
                                           InventoryPostingPort inventory,
                                           DomainEventPublisher eventPublisher,
                                           BigDecimal systemDefaultLaborRate,
                                           BigDecimal systemDefaultOverheadRate) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.workOrderRepository = Objects.requireNonNull(workOrderRepository, "workOrderRepository 不能为空");
        this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository 不能为空");
        this.reportRepository = Objects.requireNonNull(reportRepository, "reportRepository 不能为空");
        this.routingRepository = Objects.requireNonNull(routingRepository, "routingRepository 不能为空");
        this.costParamRepository = Objects.requireNonNull(costParamRepository, "costParamRepository 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
        this.systemDefaultLaborRate = systemDefaultLaborRate != null ? systemDefaultLaborRate : BigDecimal.ZERO;
        this.systemDefaultOverheadRate = systemDefaultOverheadRate != null ? systemDefaultOverheadRate : BigDecimal.ZERO;
    }

    // ===============================================================
    // 建单（create）
    // ===============================================================

    /**
     * 创建月末成本结转单（草稿）：按工单逐行归集料/工/费 + 约当产量法分摊完工/在产。
     *
     * @param docNo    单号（PC- 前缀，app 层生成）
     * @param period   账期键 yyyyMM
     * @param remark   备注（可空）
     * @param lines    每工单一行的输入（含期末在产数量/完工程度）
     * @param operator 操作人
     */
    @Audited(action = "production_cost_settlement.create", targetType = "production_cost_settlement")
    public ProductionCostSettlement create(String docNo, String period, String remark,
                                           List<ProductionCostSettlementLineInput> lines,
                                           String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(period, "账期不能为空");
        period = period.strip();   // 统一规整账期键（评审 P3-5：建单入口 strip，避免带空白存库致 YearMonth.parse 错配）
        Objects.requireNonNull(lines, "结转行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("成本结转单至少要有一行（本期无待结转工单）");
        }

        ProductionCostParam param = resolveParam(period);

        List<ProductionCostSettlementLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (ProductionCostSettlementLineInput input : lines) {
            domainLines.add(buildLine(lineNo++, input, param));
        }

        ProductionCostSettlement settlement = ProductionCostSettlement.create(
                docNo, period, remark, domainLines, operator);
        settlement.registerEventPublisher(eventPublisher);
        repository.save(settlement);
        return settlement;
    }

    /** 装载工单/领料/报工/工艺路线，算料工费 + 约当法分摊，构造一行。 */
    private ProductionCostSettlementLine buildLine(int lineNo, ProductionCostSettlementLineInput input,
                                                   ProductionCostParam param) {
        String woDocNo = Objects.requireNonNull(input.workOrderDocNo(), "工单号不能为空");
        WorkOrder wo = workOrderRepository.findByDocNo(woDocNo)
                .orElseThrow(() -> new WorkOrderNotFoundException(woDocNo));
        if (wo.getStatus() != DocumentStatus.EXECUTING && wo.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalArgumentException("工单[" + woDocNo + "] 当前状态 " + wo.getStatus()
                    + " 不可成本结转（仅 EXECUTING/COMPLETED 工单可结转）");
        }

        BigDecimal wipQty = input.wipQty() != null ? input.wipQty() : BigDecimal.ZERO;
        BigDecimal wipPct = input.wipCompletionPct() != null ? input.wipCompletionPct() : BigDecimal.ZERO;
        validateWip(woDocNo, wipQty, wipPct);

        // 三要素归集
        BigDecimal materialCost = sumIssuedCostForWorkOrder(woDocNo);
        LaborOverhead lo = sumLaborOverheadForWorkOrder(woDocNo, param);
        BigDecimal laborCost = lo.labor();
        BigDecimal overheadCost = lo.overhead();

        // 完工量 = 工单累计完工量（来自报工回写 completedQty）
        BigDecimal completedQty = wo.getCompletedQty() == null ? BigDecimal.ZERO : wo.getCompletedQty();

        // 约当产量法分摊（仅工费参与约当量；料 100% 随完工结转）
        Allocation alloc = allocate(laborCost.add(overheadCost), completedQty, wipQty, wipPct);

        // 完工应负担成本 = 完工料 + 完工工费。料 100% 随完工结转 → 完工料 = materialCost（在产不含料）。
        BigDecimal completedCost = materialCost.add(alloc.completedLaborOverhead())
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        BigDecimal wipCost = alloc.wipLaborOverhead();

        // 前期已结转完工工费锚点（防分批跨月重复，R-T06-7）
        BigDecimal alreadyTransferred = repository.sumTransferredLaborOverheadByWorkOrder(woDocNo);

        return ProductionCostSettlementLine.create(lineNo, woDocNo, materialCost, laborCost,
                overheadCost, completedQty, completedCost, wipQty, wipPct, wipCost, alreadyTransferred);
    }

    // ===============================================================
    // 约当产量法纯函数（可独立单测，ADR §2）
    // ===============================================================

    /**
     * 约当产量法分摊（纯函数，无 IO）：把本期工费总额按完工量与在产约当量分摊到完工/在产。
     *
     * <pre>
     *   在产约当量   = wipQty × wipCompletionPct / 100
     *   总约当产量   = completedQty + 在产约当量
     *   单位工费     = 工费总额 / 总约当产量            （总约当=0 时单位工费=0，全部归完工）
     *   在产应负担   = (在产约当量 × 单位工费) 取 2 位
     *   完工应负担   = 工费总额 − 在产应负担             （尾差并入完工，R-T06-5，保证两部分加总=工费总额无丢分）
     * </pre>
     *
     * @param laborOverheadTotal 本期工费总额（料不参与，2 位）
     * @param completedQty       本期完工入库量（≥ 0，6 位）
     * @param wipQty             期末在产数量（≥ 0，6 位）
     * @param wipCompletionPct   在产完工程度（0–100，2 位）
     * @return 完工/在产工费分摊（两者加总 == laborOverheadTotal）
     */
    public static Allocation allocate(BigDecimal laborOverheadTotal, BigDecimal completedQty,
                                      BigDecimal wipQty, BigDecimal wipCompletionPct) {
        BigDecimal total = laborOverheadTotal == null ? BigDecimal.ZERO : laborOverheadTotal;
        total = total.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        BigDecimal completed = completedQty == null ? BigDecimal.ZERO : completedQty;
        BigDecimal wip = wipQty == null ? BigDecimal.ZERO : wipQty;
        BigDecimal pct = wipCompletionPct == null ? BigDecimal.ZERO : wipCompletionPct;

        // 在产约当量 = wipQty × pct / 100
        BigDecimal wipEquivalent = wip.multiply(pct)
                .divide(new BigDecimal("100"), CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
        // 总约当产量
        BigDecimal totalEquivalent = completed.add(wipEquivalent);

        if (total.signum() == 0 || totalEquivalent.signum() <= 0) {
            // 无工费 或 无任何（完工+在产约当）产出：全部归完工（在产 0），不除零
            return new Allocation(total, BigDecimal.ZERO.setScale(CostingStrategy.AMOUNT_SCALE),
                    wipEquivalent, totalEquivalent);
        }

        // 单位工费（6 位）
        BigDecimal unitCost = total.divide(totalEquivalent,
                CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
        // 在产应负担（2 位）
        BigDecimal wipShare = wipEquivalent.multiply(unitCost)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        // 在产不能超过工费总额（极端舍入兜底）
        if (wipShare.compareTo(total) > 0) {
            wipShare = total;
        }
        // 完工应负担 = 总额 − 在产（尾差并入完工，R-T06-5）
        BigDecimal completedShare = total.subtract(wipShare)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
        return new Allocation(completedShare, wipShare, wipEquivalent, totalEquivalent);
    }

    /**
     * 约当法分摊结果（纯值对象）。
     *
     * @param completedLaborOverhead 完工应负担工费（2 位；含尾差）
     * @param wipLaborOverhead       在产应负担工费（2 位）
     * @param wipEquivalent          在产约当量（6 位，备查）
     * @param totalEquivalent        总约当产量（6 位，备查）
     */
    public record Allocation(BigDecimal completedLaborOverhead, BigDecimal wipLaborOverhead,
                             BigDecimal wipEquivalent, BigDecimal totalEquivalent) {
    }

    // ===============================================================
    // 状态流转
    // ===============================================================

    /** 审核：DRAFT → APPROVED。 */
    @Audited(action = "production_cost_settlement.approve", targetType = "production_cost_settlement")
    public ProductionCostSettlement approve(String docNo, String operator) {
        requireOperator(operator);
        ProductionCostSettlement settlement = get(docNo);
        settlement.registerEventPublisher(eventPublisher);
        settlement.approve(operator);
        repository.save(settlement);
        return settlement;
    }

    /** 作废：仅 DRAFT 可作废。 */
    @Audited(action = "production_cost_settlement.cancel", targetType = "production_cost_settlement")
    public ProductionCostSettlement cancel(String docNo, String operator) {
        requireOperator(operator);
        ProductionCostSettlement settlement = get(docNo);
        settlement.registerEventPublisher(eventPublisher);
        settlement.cancel(operator);
        repository.save(settlement);
        return settlement;
    }

    /**
     * 过账：APPROVED → EXECUTING → COMPLETED，每行 COST_ADJUST 追加完工工费增量到产成品仓。
     *
     * <p>GL 出凭证由 app 层在本方法返回后于同事务内调用（领域层不依赖 GL）。本方法只负责
     * 库存侧追加（增量防重复，照 T05）+ 回填幂等键 + 状态流转 + save。完工工费增量 ≤ 0 的行
     * 跳过 COST_ADJUST（无金额无调整，料工费=0 或前期已全额结转的工单），不报错（D 设计）。
     *
     * @param docNo    结转单号
     * @param operator 操作人
     */
    @Audited(action = "production_cost_settlement.post", targetType = "production_cost_settlement")
    public ProductionCostSettlement post(String docNo, String operator) {
        requireOperator(operator);
        ProductionCostSettlement settlement = get(docNo);
        settlement.registerEventPublisher(eventPublisher);

        // APPROVED → EXECUTING
        settlement.startExecution(operator);

        // 逐行追加完工工费增量到产成品库存（COST_ADJUST，NEUTRAL：调金额不调量）
        List<StockMovementCommand> batch = new ArrayList<>();
        for (ProductionCostSettlementLine line : settlement.getLines()) {
            String idemKey = idempotencyKey(settlement.getDocNo(), line.getLineNo());
            line.assignCostAdjustIdemKey(idemKey);

            BigDecimal increment = line.incrementalLaborOverhead();
            if (increment.signum() <= 0) {
                continue; // 无完工工费增量（料工费=0 或已全额结转）→ 跳过，不出库存流水
            }
            WorkOrder wo = workOrderRepository.findByDocNo(line.getWorkOrderDocNo())
                    .orElseThrow(() -> new WorkOrderNotFoundException(line.getWorkOrderDocNo()));
            Long warehouseId = wo.getWarehouseId();
            if (warehouseId == null) {
                throw new IllegalArgumentException("工单[" + line.getWorkOrderDocNo()
                        + "] 未指定生产仓库，无法追加完工工费到产成品库存");
            }
            batch.add(new CostAdjustCommand(warehouseId, wo.getProductId(), increment,
                    SRC_DOC_TYPE, settlement.getDocNo(), line.getLineNo(), idemKey));
        }
        if (!batch.isEmpty()) {
            inventory.execute(batch, operator);
        }

        // EXECUTING → COMPLETED
        settlement.complete(operator);
        repository.save(settlement);
        return settlement;
    }

    // ===============================================================
    // 查询
    // ===============================================================

    /** 按单号查询（不存在抛 ProductionCostSettlementNotFoundException → 404）。 */
    public ProductionCostSettlement get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new ProductionCostSettlementNotFoundException(docNo));
    }

    /** 分页查询。 */
    public PageResult<ProductionCostSettlement> search(ProductionCostSettlementQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ===============================================================
    // 成本归集辅助
    // ===============================================================

    /** 解析账期成本参数（无 production_cost_param 行时用系统级默认兜底）。 */
    private ProductionCostParam resolveParam(String period) {
        Optional<ProductionCostParam> found = costParamRepository.findByPeriod(period.strip());
        return found.orElseGet(() ->
                new ProductionCostParam(period.strip(), systemDefaultLaborRate, systemDefaultOverheadRate));
    }

    /**
     * 汇总该工单所有 COMPLETED 领料单各行 issuedCost 之和（料费口径同 T05）。
     */
    private BigDecimal sumIssuedCostForWorkOrder(String workOrderDocNo) {
        int page = 1;
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

    /**
     * 汇总该工单所有 COMPLETED 报工单的工/费：
     * 工 = Σ(行 reportedHours × 工序 costRate，无 rate 用默认人工费率)；
     * 费 = Σ(行 reportedHours × 制造费用率)。
     * 工序费率取自该工单产品的当前 ENABLED 工艺路线（按 operationSeqNo 匹配；缺路线/缺序号用默认人工费率）。
     */
    private LaborOverhead sumLaborOverheadForWorkOrder(String workOrderDocNo, ProductionCostParam param) {
        // 工序费率表（按产品的 active 工艺路线）：seqNo → costRate
        Map<Integer, BigDecimal> rateBySeq = new HashMap<>();
        workOrderRepository.findByDocNo(workOrderDocNo).ifPresent(wo ->
                routingRepository.findActiveByProductId(wo.getProductId()).ifPresent(routing -> {
                    for (RoutingOperation op : routing.getOperations()) {
                        if (op.costRate() != null) {
                            rateBySeq.put(op.sequenceNo(), op.costRate());
                        }
                    }
                }));

        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal overhead = BigDecimal.ZERO;
        int page = 1;
        while (true) {
            var result = reportRepository.search(new ProductionReportQuery(workOrderDocNo,
                    DocumentStatus.COMPLETED, page, 200));
            for (ProductionReport pr : result.items()) {
                for (ProductionReportLine prLine : pr.getLines()) {
                    BigDecimal hours = prLine.getReportedHours();
                    BigDecimal rate = resolveLaborRate(prLine.getOperationSeqNo(), rateBySeq, param);
                    labor = labor.add(hours.multiply(rate));
                    overhead = overhead.add(hours.multiply(param.overheadRate()));
                }
            }
            if (result.items().size() < 200) {
                break;
            }
            page++;
        }
        return new LaborOverhead(
                labor.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING),
                overhead.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING));
    }

    /** 工序费率：有工序 costRate 用之，否则用默认人工费率（R-T06-2）。 */
    private static BigDecimal resolveLaborRate(Integer operationSeqNo, Map<Integer, BigDecimal> rateBySeq,
                                               ProductionCostParam param) {
        if (operationSeqNo != null) {
            BigDecimal rate = rateBySeq.get(operationSeqNo);
            if (rate != null) {
                return rate;
            }
        }
        return param.defaultLaborRate();
    }

    private record LaborOverhead(BigDecimal labor, BigDecimal overhead) {
    }

    private static void validateWip(String woDocNo, BigDecimal wipQty, BigDecimal wipPct) {
        if (wipQty.signum() < 0) {
            throw new IllegalArgumentException("工单[" + woDocNo + "] 在产数量不能为负: " + wipQty.toPlainString());
        }
        if (wipPct.signum() < 0 || wipPct.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("工单[" + woDocNo + "] 完工程度必须在 0–100 之间: "
                    + wipPct.toPlainString());
        }
    }

    /** 幂等键：PRODUCTION_COST_SETTLEMENT:PC单号:行号 */
    static String idempotencyKey(String docNo, int lineNo) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
