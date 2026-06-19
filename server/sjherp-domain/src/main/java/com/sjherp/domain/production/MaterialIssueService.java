package com.sjherp.domain.production;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 领料单领域服务（M5-T04）。
 *
 * <p>所有领料单写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零 Spring 依赖。
 * 过账（post）编排：工单 EXECUTING 校验 → 状态推进 → 批量 OutboundCommand(PRODUCTION_ISSUE)
 * → 经 {@link InventoryPostingPort} 唯一入口过账 → 回填 issuedCost → COMPLETED。
 * 调用方（app 层 MaterialIssueAppService）须包外层 @Transactional 保原子性。
 */
public class MaterialIssueService {

    /** 库存流水来源单据类型 */
    static final String SRC_DOC_TYPE = "MATERIAL_ISSUE";

    private final MaterialIssueRepository repository;
    private final WorkOrderRepository workOrderRepository;
    private final InventoryPostingPort inventory;
    private final DomainEventPublisher eventPublisher;

    public MaterialIssueService(MaterialIssueRepository repository,
                                 WorkOrderRepository workOrderRepository,
                                 InventoryPostingPort inventory,
                                 DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.workOrderRepository = Objects.requireNonNull(workOrderRepository, "workOrderRepository 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建领料单（草稿）。
     *
     * <p>校验：工单必须存在且为 EXECUTING（已开工）状态；至少一行；数量 > 0。
     *
     * @param docNo          单号（MI- 前缀，app 层 DocumentNumberGenerator 生成）
     * @param workOrderDocNo 关联工单号
     * @param warehouseId    领料仓库 id
     * @param remark         备注（可空）
     * @param lines          领料行输入
     * @param operator       创建人
     */
    @Audited(action = "material_issue.create", targetType = "material_issue")
    public MaterialIssue create(String docNo, String workOrderDocNo, long warehouseId,
                                 String remark, List<MaterialIssueLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(workOrderDocNo, "关联工单号不能为空");
        Objects.requireNonNull(lines, "领料行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("领料单至少要有一行");
        }

        // 工单必须存在且为 EXECUTING 状态
        WorkOrder wo = workOrderRepository.findByDocNo(workOrderDocNo)
                .orElseThrow(() -> new WorkOrderNotFoundException(workOrderDocNo));
        if (wo.getStatus() != DocumentStatus.EXECUTING) {
            throw new IllegalArgumentException("工单[" + workOrderDocNo + "] 当前状态 " + wo.getStatus()
                    + " 不是开工状态（EXECUTING），不可领料");
        }

        // 构建领料行
        List<MaterialIssueLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (MaterialIssueLineInput input : lines) {
            domainLines.add(MaterialIssueLine.create(lineNo++, input.productId(),
                    input.requiredQty(), input.quantity(), input.unitId()));
        }

        MaterialIssue mi = MaterialIssue.create(docNo, workOrderDocNo, warehouseId, remark,
                domainLines, operator);
        mi.registerEventPublisher(eventPublisher);
        repository.save(mi);
        return mi;
    }

    /** 审核领料单：DRAFT → APPROVED。 */
    @Audited(action = "material_issue.approve", targetType = "material_issue")
    public MaterialIssue approve(String docNo, String operator) {
        requireOperator(operator);
        MaterialIssue mi = get(docNo);
        mi.registerEventPublisher(eventPublisher);
        mi.approve(operator);
        repository.save(mi);
        return mi;
    }

    /** 作废领料单：仅 DRAFT 可作废。 */
    @Audited(action = "material_issue.cancel", targetType = "material_issue")
    public MaterialIssue cancel(String docNo, String operator) {
        requireOperator(operator);
        MaterialIssue mi = get(docNo);
        mi.registerEventPublisher(eventPublisher);
        mi.cancel(operator);
        repository.save(mi);
        return mi;
    }

    /**
     * 过账领料单：APPROVED → EXECUTING → COMPLETED，每行 PRODUCTION_ISSUE 组一批同事务原子过账。
     *
     * <p>过账后把每行 issuedCost（出库 totalCost 的绝对值）回填到领料行。
     * 库存不足时 InventoryService 抛 InsufficientStockException，整批回滚（由外层 @Transactional 保证）。
     *
     * @param docNo    领料单号
     * @param operator 操作人
     */
    @Audited(action = "material_issue.post", targetType = "material_issue")
    public MaterialIssue post(String docNo, String operator) {
        requireOperator(operator);
        MaterialIssue mi = get(docNo);
        mi.registerEventPublisher(eventPublisher);

        // 状态：APPROVED → EXECUTING
        mi.startExecution(operator);

        // ① 组批：每行 PRODUCTION_ISSUE（移动加权，不传 overriddenUnitCost），幂等键 MATERIAL_ISSUE:docNo:行号
        List<StockMovementCommand> batch = new ArrayList<>(mi.getLines().size());
        for (MaterialIssueLine line : mi.getLines()) {
            batch.add(new OutboundCommand(mi.getWarehouseId(), line.getProductId(),
                    InventoryTxnType.PRODUCTION_ISSUE, line.getQuantity(),
                    SRC_DOC_TYPE, mi.getDocNo(), line.getLineNo(),
                    idempotencyKey(mi.getDocNo(), line.getLineNo())));
        }

        // ② 一次原子过账（库存不足整批回滚）
        List<StockMovementResult> results = inventory.execute(batch, operator);
        Map<Integer, StockMovementResult> byLineNo = new LinkedHashMap<>();
        for (StockMovementResult result : results) {
            byLineNo.put(result.srcLineNo(), result);
        }

        // ③ 回填每行 issuedCost（出库 totalCost 为负，取绝对值）
        for (MaterialIssueLine line : mi.getLines()) {
            StockMovementResult result = byLineNo.get(line.getLineNo());
            if (result == null) {
                throw new IllegalStateException("领料单[" + mi.getDocNo() + "] 行号 "
                        + line.getLineNo() + " 未取得库存过账结果（issuedCost 无从回填）");
            }
            line.assignIssuedCost(result.totalCost().negate());
        }

        // ④ EXECUTING → COMPLETED
        mi.complete(operator);
        repository.save(mi);
        return mi;
    }

    /** 按单号查询（不存在抛 MaterialIssueNotFoundException → 404） */
    public MaterialIssue get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new MaterialIssueNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<MaterialIssue> search(MaterialIssueQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    /** 幂等键：MATERIAL_ISSUE:docNo:行号 */
    static String idempotencyKey(String docNo, int lineNo) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
