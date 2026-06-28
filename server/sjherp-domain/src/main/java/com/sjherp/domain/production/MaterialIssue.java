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
 * 领料单聚合根（M5-T04）。
 *
 * <p>引用某工单（{@link #workOrderDocNo}），从指定仓库（{@link #warehouseId}）领取生产所需子件。
 * 行项目（{@link MaterialIssueLine}）逐行对应一个子件的一次领料量。
 * 走 {@link BusinessDocument} 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；
 * DRAFT → CANCELLED）。不覆写 beforeTransition（使用基类六态全表，同 SalesDelivery）。
 *
 * <p>一个工单可关联多张领料单（JIT 分批 + 补料，同"一订单多出库"模式）。
 * 领料单不实现 COMPLETED → REVERSED 冲销（退料用独立 {@link MaterialReturn} 替代，设计真源 R7）。
 */
public final class MaterialIssue extends BusinessDocument implements AuditTarget {

    /** 数据库自增主键（建单时为 null，仓储 save 后回填） */
    private Long id;

    /** 关联工单号 */
    private final String workOrderDocNo;

    /** 领料仓库 id */
    private final long warehouseId;

    /** 备注（可空） */
    private final String remark;

    /** 领料行（建单后行集合不变；issuedCost 过账后回填） */
    private final List<MaterialIssueLine> lines;

    private MaterialIssue(String docNo, String workOrderDocNo, long warehouseId, String remark,
                          List<MaterialIssueLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.workOrderDocNo = Objects.requireNonNull(workOrderDocNo, "关联工单号不能为空");
        this.warehouseId = warehouseId;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建领料单（草稿）。要求工单号非空、至少一行、行号不重复。
     *
     * @param docNo           单号（MI- 前缀，由 DocumentNumberGenerator 生成）
     * @param workOrderDocNo  关联工单号
     * @param warehouseId     领料仓库 id
     * @param remark          备注（可空）
     * @param lines           领料行（至少一行，行号单据内唯一）
     * @param operator        创建人
     */
    public static MaterialIssue create(String docNo, String workOrderDocNo, long warehouseId,
                                       String remark, List<MaterialIssueLine> lines, String operator) {
        Objects.requireNonNull(lines, "领料行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("领料单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(MaterialIssueLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("领料单行号不能重复");
        }
        return new MaterialIssue(docNo, workOrderDocNo, warehouseId, remark, lines, operator);
    }

    /**
     * 持久层重建工厂（完整签名，含 id / 冲销链路 / updatedBy，不重跑业务校验）。
     * 供 {@link com.sjherp.infra.persistence.production.JdbcMaterialIssueRepository} 调用。
     */
    public static MaterialIssue restore(long id, String docNo, String workOrderDocNo, long warehouseId,
                                        String remark, DocumentStatus status,
                                        String reversalOfId, String reversedById,
                                        List<MaterialIssueLine> lines, String createdBy, String updatedBy) {
        MaterialIssue mi = new MaterialIssue(docNo, workOrderDocNo, warehouseId, remark, lines, createdBy);
        mi.id = id;
        mi.restoreStatus(status);
        mi.restoreReversalLinks(reversalOfId, reversedById);
        return mi;
    }

    /** 数据库自增主键回填（仅供仓储层调用） */
    public void assignId(long id) {
        this.id = id;
    }

    /** 持久层重建工厂（简化签名，兼容已有测试用例，无 id/冲销字段） */
    public static MaterialIssue restore(String docNo, String workOrderDocNo, long warehouseId,
                                        String remark, DocumentStatus status,
                                        List<MaterialIssueLine> lines, String createdBy) {
        MaterialIssue mi = new MaterialIssue(docNo, workOrderDocNo, warehouseId, remark, lines, createdBy);
        mi.restoreStatus(status);
        return mi;
    }

    /** 领料成本合计（过账后各行 issuedCost 之和；未回填的行计 0） */
    public BigDecimal totalIssuedCost() {
        return lines.stream()
                .map(line -> line.getIssuedCost() == null ? BigDecimal.ZERO : line.getIssuedCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() { return id; }
    public String getWorkOrderDocNo() { return workOrderDocNo; }
    public long getWarehouseId() { return warehouseId; }
    public String getRemark() { return remark; }

    /** 行只读视图（防外部增删行） */
    public List<MaterialIssueLine> getLines() { return List.copyOf(lines); }

    // ---------------------------------------------------------------- AuditTarget

    @Override
    public Long auditTargetId() { return id; }

    @Override
    public String auditTargetCode() { return getDocNo(); }

    @Override
    public String auditSummary() {
        return "关联工单=" + workOrderDocNo + ", 仓库=" + warehouseId + ", 状态=" + getStatus()
                + ", 行数=" + lines.size() + ", 领料成本=" + totalIssuedCost().toPlainString()
                + ", 说明=" + AuditTarget.text(remark);
    }
}
