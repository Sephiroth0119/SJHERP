package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 退料单领域服务（M5-T04）。
 *
 * <p>退料过账：APPROVED → EXECUTING → COMPLETED，每行 PRODUCTION_RETURN 按原领料成本
 * 入库（显式 unitCost = issuedCost / quantity，避期间进货成本漂移）。
 * 调用方（app 层 MaterialReturnAppService）须包外层 @Transactional 保原子性。
 */
public class MaterialReturnService {

    /** 库存流水来源单据类型 */
    static final String SRC_DOC_TYPE = "MATERIAL_RETURN";

    private final MaterialReturnRepository repository;
    private final MaterialIssueRepository issueRepository;
    private final InventoryPostingPort inventory;
    private final DomainEventPublisher eventPublisher;

    public MaterialReturnService(MaterialReturnRepository repository,
                                  MaterialIssueRepository issueRepository,
                                  InventoryPostingPort inventory,
                                  DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository 不能为空");
        this.inventory = Objects.requireNonNull(inventory, "inventory 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建退料单（草稿）。
     *
     * <p>校验：原领料单必须存在且已过账（COMPLETED）；至少一行；数量 > 0。
     *
     * @param docNo               单号（MR- 前缀）
     * @param materialIssueDocNo  原领料单号
     * @param warehouseId         退料仓库 id
     * @param remark              备注（可空）
     * @param lines               退料行输入
     * @param operator            创建人
     */
    @Audited(action = "material_return.create", targetType = "material_return")
    public MaterialReturn create(String docNo, String materialIssueDocNo, long warehouseId,
                                  String remark, List<MaterialReturnLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(materialIssueDocNo, "原领料单号不能为空");
        Objects.requireNonNull(lines, "退料行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("退料单至少要有一行");
        }

        // 原领料单必须存在且已过账
        MaterialIssue mi = issueRepository.findByDocNo(materialIssueDocNo)
                .orElseThrow(() -> new MaterialIssueNotFoundException(materialIssueDocNo));
        if (mi.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalArgumentException("原领料单[" + materialIssueDocNo + "] 当前状态 "
                    + mi.getStatus() + " 尚未过账，不可退料");
        }

        // 防超退（数据模型完整性红线，评审 P1/P2）：退料商品必须在原领料单已过账领过，
        // 且本退料单内同商品累计退料量 ≤ 原领料单该商品实领量。
        // 注：跨多张退料单的累计退料控制本批不做（R4，独立单据各自校验，已登记）。
        Map<Long, BigDecimal> issuedByProduct = new LinkedHashMap<>();
        for (MaterialIssueLine il : mi.getLines()) {
            issuedByProduct.merge(il.getProductId(), il.getQuantity(), BigDecimal::add);
        }
        Map<Long, BigDecimal> returnByProduct = new LinkedHashMap<>();
        for (MaterialReturnLineInput input : lines) {
            BigDecimal issued = issuedByProduct.get(input.productId());
            if (issued == null) {
                throw new IllegalArgumentException("退料商品 id=" + input.productId()
                        + " 不在原领料单[" + materialIssueDocNo + "] 的已领明细中，不可退料");
            }
            BigDecimal cumulative = returnByProduct.merge(input.productId(), input.quantity(), BigDecimal::add);
            if (cumulative.compareTo(issued) > 0) {
                throw new IllegalArgumentException("商品 id=" + input.productId()
                        + " 退料量 " + cumulative.toPlainString() + " 超过原领料实领量 "
                        + issued.toPlainString() + "，不可超退");
            }
        }

        // 构建退料行
        List<MaterialReturnLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (MaterialReturnLineInput input : lines) {
            domainLines.add(MaterialReturnLine.create(lineNo++, input.productId(),
                    input.quantity(), input.unitId(), input.srcIssueLineNo()));
        }

        MaterialReturn mr = MaterialReturn.create(docNo, materialIssueDocNo, warehouseId, remark,
                domainLines, operator);
        mr.registerEventPublisher(eventPublisher);
        repository.save(mr);
        return mr;
    }

    /** 审核退料单：DRAFT → APPROVED。 */
    @Audited(action = "material_return.approve", targetType = "material_return")
    public MaterialReturn approve(String docNo, String operator) {
        requireOperator(operator);
        MaterialReturn mr = get(docNo);
        mr.registerEventPublisher(eventPublisher);
        mr.approve(operator);
        repository.save(mr);
        return mr;
    }

    /**
     * 过账退料单：APPROVED → EXECUTING → COMPLETED。
     *
     * <p>按原领料成本退回：逐行查原领料单对应商品行的 issuedCost/quantity 算单价，
     * 走 InboundCommand(PRODUCTION_RETURN, unitCost=原单价)，避期间进货成本漂移。
     * 幂等键 MATERIAL_RETURN:MR-xxx:行号。
     *
     * @param docNo    退料单号
     * @param operator 操作人
     */
    @Audited(action = "material_return.post", targetType = "material_return")
    public MaterialReturn post(String docNo, String operator) {
        requireOperator(operator);
        MaterialReturn mr = get(docNo);
        mr.registerEventPublisher(eventPublisher);

        // 加载原领料单（已过账，用于取原单价）
        MaterialIssue mi = issueRepository.findByDocNo(mr.getMaterialIssueDocNo())
                .orElseThrow(() -> new MaterialIssueNotFoundException(mr.getMaterialIssueDocNo()));

        // 状态：APPROVED → EXECUTING
        mr.startExecution(operator);

        // ① 组批：每行 PRODUCTION_RETURN，unitCost = 原领料单该商品行的单价
        List<StockMovementCommand> batch = new ArrayList<>(mr.getLines().size());
        for (MaterialReturnLine line : mr.getLines()) {
            BigDecimal unitCost = resolveUnitCost(mi, line.getProductId(), mr.getDocNo());
            batch.add(new InboundCommand(mr.getWarehouseId(), line.getProductId(),
                    InventoryTxnType.PRODUCTION_RETURN, line.getQuantity(), unitCost,
                    null,   // transferOutKey
                    SRC_DOC_TYPE, mr.getDocNo(), line.getLineNo(),
                    idempotencyKey(mr.getDocNo(), line.getLineNo())));
        }

        // ② 一次原子过账
        List<StockMovementResult> results = inventory.execute(batch, operator);
        Map<Integer, StockMovementResult> byLineNo = new LinkedHashMap<>();
        for (StockMovementResult result : results) {
            byLineNo.put(result.srcLineNo(), result);
        }

        // ③ 回填每行 returnedCost（入库 totalCost 为正）
        for (MaterialReturnLine line : mr.getLines()) {
            StockMovementResult result = byLineNo.get(line.getLineNo());
            if (result == null) {
                throw new IllegalStateException("退料单[" + mr.getDocNo() + "] 行号 "
                        + line.getLineNo() + " 未取得库存过账结果");
            }
            line.assignReturnedCost(result.totalCost());
        }

        // ④ EXECUTING → COMPLETED
        mr.complete(operator);
        repository.save(mr);
        return mr;
    }

    /** 按单号查询（不存在抛 MaterialReturnNotFoundException → 404） */
    public MaterialReturn get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new MaterialReturnNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<MaterialReturn> search(MaterialReturnQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    /**
     * 从原领料单找该商品的已过账单价（issuedCost / quantity）。
     * 若原领料单有多行同商品取第一行（FIFO 简化）。
     * 若无该商品已过账领料行则**拒绝退料**（不可零成本入库稀释移动加权，评审 P2）。
     */
    private BigDecimal resolveUnitCost(MaterialIssue mi, long productId, String returnDocNo) {
        return mi.getLines().stream()
                .filter(l -> l.getProductId() == productId && l.getIssuedCost() != null)
                .findFirst()
                .map(l -> l.getIssuedCost().divide(l.getQuantity(),
                        CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING))
                .orElseThrow(() -> new IllegalArgumentException("退料单[" + returnDocNo
                        + "] 商品 id=" + productId + " 在原领料单无已过账领料成本，无法按原成本退回"));
    }

    /** 幂等键：MATERIAL_RETURN:docNo:行号 */
    static String idempotencyKey(String docNo, int lineNo) {
        return SRC_DOC_TYPE + ":" + docNo + ":" + lineNo;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
