package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 报工单聚合根（M5-T05）。
 *
 * <p>一张报工单 = 一次车间完工动作：工时记录（{@link ProductionReportLine} 行集合）
 * + 完工入库数量 + 报废数量（记录不入库）+ 过账后回填完工入库成本。
 *
 * <p>走 {@link BusinessDocument} 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；
 * DRAFT → CANCELLED）。不覆写 beforeTransition（使用基类六态全表，同 MaterialIssue）。
 * 完工入库 = APPROVED → EXECUTING → COMPLETED 双步，由 {@link ProductionReportService#post} 编排。
 *
 * <p>本批不实现 COMPLETED → REVERSED 冲销（完工冲销/退库留后续，R6）。
 */
public final class ProductionReport extends BusinessDocument implements AuditTarget {

    /** 数据库自增主键（建单时为 null，仓储 save 后回填） */
    private Long id;

    /** 关联工单号（必填，过账时校验工单 EXECUTING） */
    private final String workOrderDocNo;

    /** 产成品入库仓库 id */
    private final long warehouseId;

    /** 生产商品 id（冗余自工单，建单时校验 == 工单 productId） */
    private final long productId;

    /** 本次合格完工入库数量（> 0，6 位小数） */
    private final BigDecimal completedQty;

    /** 本次报废数量（≥ 0，记录不入库；默认 0） */
    private final BigDecimal scrapQty;

    /** 计量单位 id */
    private final long unitId;

    /**
     * 完工入库成本（正数口径，2 位小数）：过账后由 {@link #assignInboundCost(BigDecimal)} 回填。
     * 值 = 本次结转料费 = 工单全部已过账领料 issuedCost 之和 − 前序报工已结转料费（inbound_cost 累计）；
     * 保证 Σ完工入库金额 ≡ Σ领料出库金额（料的进出守恒，设计真源 R1）。精确分摊（约当产量/标准成本）留 T06。
     */
    private BigDecimal inboundCost;

    /** 备注（可空） */
    private final String remark;

    /** 工时行（至少一行；建单后行集合不变） */
    private final List<ProductionReportLine> lines;

    private ProductionReport(String docNo, String workOrderDocNo, long warehouseId, long productId,
                              BigDecimal completedQty, BigDecimal scrapQty, long unitId,
                              String remark, List<ProductionReportLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.workOrderDocNo = Objects.requireNonNull(workOrderDocNo, "关联工单号不能为空");
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.completedQty = Objects.requireNonNull(completedQty, "完工数量不能为空");
        this.scrapQty = scrapQty != null ? scrapQty : BigDecimal.ZERO;
        this.unitId = unitId;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建报工单（草稿）。
     *
     * @param docNo           单号（PR- 前缀，由 DocumentNumberGenerator 生成）
     * @param workOrderDocNo  关联工单号（工单须 EXECUTING，由 Service 层校验）
     * @param warehouseId     产成品入库仓库 id
     * @param productId       生产商品 id（须与工单 productId 一致，由 Service 层校验）
     * @param completedQty    本次完工入库数量（> 0）
     * @param scrapQty        本次报废数量（≥ 0，可为 null，默认 0）
     * @param unitId          计量单位 id
     * @param remark          备注（可空）
     * @param lines           工时行（至少一行）
     * @param operator        创建人
     */
    public static ProductionReport create(String docNo, String workOrderDocNo, long warehouseId,
                                           long productId, BigDecimal completedQty, BigDecimal scrapQty,
                                           long unitId, String remark,
                                           List<ProductionReportLine> lines, String operator) {
        Objects.requireNonNull(completedQty, "完工数量不能为空");
        if (completedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("完工数量必须大于 0: " + completedQty.toPlainString());
        }
        if (scrapQty != null && scrapQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("报废数量不能为负: " + scrapQty.toPlainString());
        }
        Objects.requireNonNull(lines, "工时行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("报工单至少要有一行工时记录");
        }
        List<Integer> lineNos = lines.stream().map(ProductionReportLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("报工单行号不能重复");
        }
        return new ProductionReport(docNo, workOrderDocNo, warehouseId, productId,
                completedQty, scrapQty, unitId, remark, lines, operator);
    }

    /**
     * 持久层重建工厂（完整签名，含 id / 冲销链路 / inboundCost，不重跑业务校验）。
     * 供 {@link com.sjherp.infra.persistence.production.JdbcProductionReportRepository} 调用。
     */
    public static ProductionReport restore(long id, String docNo, String workOrderDocNo, long warehouseId,
                                            long productId, BigDecimal completedQty, BigDecimal scrapQty,
                                            long unitId, BigDecimal inboundCost, String remark,
                                            DocumentStatus status,
                                            String reversalOfId, String reversedById,
                                            List<ProductionReportLine> lines,
                                            String createdBy, String updatedBy) {
        ProductionReport pr = new ProductionReport(docNo, workOrderDocNo, warehouseId, productId,
                completedQty, scrapQty, unitId, remark, lines, createdBy);
        pr.id = id;
        pr.inboundCost = inboundCost;
        pr.restoreStatus(status);
        pr.restoreReversalLinks(reversalOfId, reversedById);
        return pr;
    }

    /** 数据库自增主键回填（仅供仓储层调用） */
    public void assignId(long id) {
        this.id = id;
    }

    /**
     * 过账后回填完工入库成本（正数口径，2 位小数）。
     * 只允许回填一次（从 null 到有值），保证幂等性。
     *
     * @param cost 完工入库成本（≥ 0，2 位小数）
     */
    public void assignInboundCost(BigDecimal cost) {
        Objects.requireNonNull(cost, "完工入库成本不能为空");
        if (this.inboundCost != null) {
            throw new IllegalStateException("完工入库成本已回填，不可重复赋值: " + this.inboundCost.toPlainString());
        }
        if (cost.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("完工入库成本不能为负: " + cost.toPlainString());
        }
        this.inboundCost = cost.setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() { return id; }
    public String getWorkOrderDocNo() { return workOrderDocNo; }
    public long getWarehouseId() { return warehouseId; }
    public long getProductId() { return productId; }
    public BigDecimal getCompletedQty() { return completedQty; }
    public BigDecimal getScrapQty() { return scrapQty; }
    public long getUnitId() { return unitId; }
    public BigDecimal getInboundCost() { return inboundCost; }
    public String getRemark() { return remark; }

    /** 行只读视图（防外部增删行） */
    public List<ProductionReportLine> getLines() { return List.copyOf(lines); }

    // ---------------------------------------------------------------- AuditTarget

    @Override
    public Long auditTargetId() { return id; }

    @Override
    public String auditTargetCode() { return getDocNo(); }

    @Override
    public String auditSummary() {
        return "关联工单=" + workOrderDocNo + ", 仓库=" + warehouseId + ", 商品=" + productId
                + ", 完工量=" + completedQty.toPlainString()
                + ", 报废量=" + scrapQty.toPlainString()
                + ", 状态=" + getStatus()
                + ", 工时行数=" + lines.size()
                + (inboundCost != null ? ", 入库成本=" + inboundCost.toPlainString() : "")
                + ", 说明=" + AuditTarget.text(remark);
    }
}
