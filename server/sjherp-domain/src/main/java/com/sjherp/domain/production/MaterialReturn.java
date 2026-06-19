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
 * 退料单聚合根（M5-T04）。
 *
 * <p>独立单据（不用负数行），引用原领料单 {@link #materialIssueDocNo}，
 * 过账走 PRODUCTION_RETURN 入库（按原领料成本 unitCost=issuedCost/quantity 归还，避期间漂移）。
 * 走 {@link BusinessDocument} 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；
 * DRAFT → CANCELLED）。不覆写 beforeTransition（使用基类六态全表）。
 */
public final class MaterialReturn extends BusinessDocument implements AuditTarget {

    /** 数据库自增主键（建单时为 null，仓储 save 后回填） */
    private Long id;

    /** 引用的原领料单号 */
    private final String materialIssueDocNo;

    /** 退料仓库 id（退回至原领料仓） */
    private final long warehouseId;

    /** 备注（可空） */
    private final String remark;

    /** 退料行 */
    private final List<MaterialReturnLine> lines;

    private MaterialReturn(String docNo, String materialIssueDocNo, long warehouseId, String remark,
                           List<MaterialReturnLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.materialIssueDocNo = Objects.requireNonNull(materialIssueDocNo, "原领料单号不能为空");
        this.warehouseId = warehouseId;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建退料单（草稿）。
     *
     * @param docNo               单号（MR- 前缀，由 DocumentNumberGenerator 生成）
     * @param materialIssueDocNo  原领料单号（必须存在且已过账，校验在 MaterialReturnService）
     * @param warehouseId         退料仓库 id
     * @param remark              备注（可空）
     * @param lines               退料行（至少一行，行号不重复）
     * @param operator            创建人
     */
    public static MaterialReturn create(String docNo, String materialIssueDocNo, long warehouseId,
                                        String remark, List<MaterialReturnLine> lines, String operator) {
        Objects.requireNonNull(lines, "退料行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("退料单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(MaterialReturnLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("退料单行号不能重复");
        }
        return new MaterialReturn(docNo, materialIssueDocNo, warehouseId, remark, lines, operator);
    }

    /**
     * 持久层重建工厂（完整签名，含 id / 冲销链路 / updatedBy，不重跑业务校验）。
     * 供 {@link com.sjherp.infra.persistence.production.JdbcMaterialReturnRepository} 调用。
     */
    public static MaterialReturn restore(long id, String docNo, String materialIssueDocNo, long warehouseId,
                                         String remark, DocumentStatus status,
                                         String reversalOfId, String reversedById,
                                         List<MaterialReturnLine> lines, String createdBy, String updatedBy) {
        MaterialReturn mr = new MaterialReturn(docNo, materialIssueDocNo, warehouseId, remark, lines, createdBy);
        mr.id = id;
        mr.restoreStatus(status);
        mr.restoreReversalLinks(reversalOfId, reversedById);
        return mr;
    }

    /** 持久层重建工厂（简化签名，兼容已有测试用例，无 id/冲销字段） */
    public static MaterialReturn restore(String docNo, String materialIssueDocNo, long warehouseId,
                                         String remark, DocumentStatus status,
                                         List<MaterialReturnLine> lines, String createdBy) {
        MaterialReturn mr = new MaterialReturn(docNo, materialIssueDocNo, warehouseId, remark, lines, createdBy);
        mr.restoreStatus(status);
        return mr;
    }

    /** 数据库自增主键回填（仅供仓储层调用） */
    public void assignId(long id) {
        this.id = id;
    }

    /** 退料成本合计（过账后各行 returnedCost 之和） */
    public BigDecimal totalReturnedCost() {
        return lines.stream()
                .map(line -> line.getReturnedCost() == null ? BigDecimal.ZERO : line.getReturnedCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public Long getId() { return id; }
    public String getMaterialIssueDocNo() { return materialIssueDocNo; }
    public long getWarehouseId() { return warehouseId; }
    public String getRemark() { return remark; }

    /** 行只读视图 */
    public List<MaterialReturnLine> getLines() { return List.copyOf(lines); }

    // ---------------------------------------------------------------- AuditTarget

    @Override
    public Long auditTargetId() { return id; }

    @Override
    public String auditTargetCode() { return getDocNo(); }

    @Override
    public String auditSummary() {
        return "原领料单=" + materialIssueDocNo + ", 仓库=" + warehouseId + ", 状态=" + getStatus()
                + ", 行数=" + lines.size() + ", 退料成本=" + totalReturnedCost().toPlainString()
                + ", 说明=" + AuditTarget.text(remark);
    }
}
