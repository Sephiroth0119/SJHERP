package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.Objects;

import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 报工单工时行（从属 {@link ProductionReport} 聚合，M5-T05）。
 *
 * <p>一行 = 一个工序的工时记录：工序快照（可空，弱关联）+ 报工工时（>0，6 位小数）+ 报工量（可空）。
 * costRate/laborCost 留 T06 归集，本批不设。
 */
public final class ProductionReportLine {

    /** 数据库自增主键（持久化后回填） */
    private Long id;

    /** 行号（单据内从 1 起） */
    private final int lineNo;

    /** 工序序号快照（可空，弱关联工艺路线） */
    private final Integer operationSeqNo;

    /** 工序名称快照（可空） */
    private final String operationName;

    /** 工作中心快照（可空，T06 归集用） */
    private final String workCenter;

    /** 报工工时（BigDecimal > 0，6 位小数） */
    private final BigDecimal reportedHours;

    /** 报工数量（可空，默认 = 头部 completedQty；6 位小数） */
    private final BigDecimal reportedQty;

    /** 计量单位 id */
    private final long unitId;

    private ProductionReportLine(Long id, int lineNo, Integer operationSeqNo, String operationName,
                                  String workCenter, BigDecimal reportedHours, BigDecimal reportedQty,
                                  long unitId) {
        this.id = id;
        this.lineNo = lineNo;
        this.operationSeqNo = operationSeqNo;
        this.operationName = operationName;
        this.workCenter = workCenter;
        this.reportedHours = Objects.requireNonNull(reportedHours, "报工工时不能为空");
        this.reportedQty = reportedQty;
        this.unitId = unitId;
    }

    /**
     * 建单工厂。
     *
     * @param lineNo          行号（≥ 1）
     * @param operationSeqNo  工序序号（可空）
     * @param operationName   工序名称快照（可空）
     * @param workCenter      工作中心快照（可空）
     * @param reportedHours   报工工时（> 0，6 位小数）
     * @param reportedQty     报工数量（可空）
     * @param unitId          计量单位 id
     */
    public static ProductionReportLine create(int lineNo, Integer operationSeqNo, String operationName,
                                               String workCenter, BigDecimal reportedHours,
                                               BigDecimal reportedQty, long unitId) {
        if (lineNo < 1) {
            throw new IllegalArgumentException("报工行号必须 >= 1: " + lineNo);
        }
        Objects.requireNonNull(reportedHours, "报工工时不能为空");
        if (reportedHours.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("报工工时必须大于 0: " + reportedHours.toPlainString());
        }
        BigDecimal scaledHours = reportedHours.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING);
        BigDecimal scaledQty = reportedQty != null
                ? reportedQty.setScale(CostingStrategy.UNIT_COST_SCALE, CostingStrategy.ROUNDING)
                : null;
        return new ProductionReportLine(null, lineNo, operationSeqNo, operationName, workCenter,
                scaledHours, scaledQty, unitId);
    }

    /** 持久层重建工厂（不重跑业务校验） */
    public static ProductionReportLine restore(long id, int lineNo, Integer operationSeqNo,
                                                String operationName, String workCenter,
                                                BigDecimal reportedHours, BigDecimal reportedQty,
                                                long unitId) {
        return new ProductionReportLine(id, lineNo, operationSeqNo, operationName, workCenter,
                reportedHours, reportedQty, unitId);
    }

    /** 仓储落库后回填自增 id（只允许一次） */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("报工行 id 已分配，不可重复分配: " + this.id);
        }
        this.id = id;
    }

    public Long getId() { return id; }
    public int getLineNo() { return lineNo; }
    public Integer getOperationSeqNo() { return operationSeqNo; }
    public String getOperationName() { return operationName; }
    public String getWorkCenter() { return workCenter; }
    public BigDecimal getReportedHours() { return reportedHours; }
    public BigDecimal getReportedQty() { return reportedQty; }
    public long getUnitId() { return unitId; }
}
