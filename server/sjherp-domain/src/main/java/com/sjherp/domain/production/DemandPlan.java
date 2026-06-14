package com.sjherp.domain.production;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 需求计划聚合根（M5-T02）。
 *
 * <p>docNo 前缀 DP-，planDate 为计划基准日，行列表整体替换（无独立生命周期的值对象）。
 * status 用 ArchiveStatus 表达启用/停用（与 BOM 档案同模式）。
 */
public class DemandPlan implements AuditTarget {

    private Long id;
    private final String docNo;
    private LocalDate planDate;
    private ArchiveStatus status;
    private String remark;
    private List<DemandPlanLine> lines;

    /** 审计四字段 */
    private final String createdBy;
    private final Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;

    /** 新建构造（id 由仓储回填）。 */
    public DemandPlan(String docNo, LocalDate planDate, String remark,
                      List<DemandPlanLine> lines, String operator) {
        this.docNo = Objects.requireNonNull(docNo, "docNo 不能为空");
        this.planDate = Objects.requireNonNull(planDate, "planDate 不能为空");
        this.remark = remark;
        this.lines = List.copyOf(Objects.requireNonNull(lines, "行列表不能为空"));
        this.status = ArchiveStatus.ENABLED;
        this.createdBy = operator;
        this.createdAt = Instant.now();
        this.updatedBy = operator;
        this.updatedAt = createdAt;
    }

    private DemandPlan(Long id, String docNo, LocalDate planDate, ArchiveStatus status,
                       String remark, List<DemandPlanLine> lines,
                       String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
        this.id = id;
        this.docNo = docNo;
        this.planDate = planDate;
        this.status = status;
        this.remark = remark;
        this.lines = List.copyOf(lines);
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** 持久化层重建聚合（restore 工厂方法，绕过业务校验直接映射数据库行）。 */
    public static DemandPlan restore(Long id, String docNo, LocalDate planDate, ArchiveStatus status,
                                     String remark, List<DemandPlanLine> lines,
                                     String createdBy, Instant createdAt,
                                     String updatedBy, Instant updatedAt) {
        return new DemandPlan(id, docNo, planDate, status, remark, lines,
                createdBy, createdAt, updatedBy, updatedAt);
    }

    /** 仓储回填 id（一次性调用，id 已存在则抛异常）。 */
    public void assignId(long id) {
        if (this.id != null) {
            throw new IllegalStateException("id 已分配，不可重复赋值: " + this.id);
        }
        this.id = id;
    }

    /** 更新计划内容（行列表整体替换）。 */
    public void update(LocalDate planDate, String remark, List<DemandPlanLine> lines, String operator) {
        this.planDate = Objects.requireNonNull(planDate, "planDate 不能为空");
        this.remark = remark;
        this.lines = List.copyOf(Objects.requireNonNull(lines, "行列表不能为空"));
        this.updatedBy = operator;
        this.updatedAt = Instant.now();
    }

    // -------- getter --------

    public Long getId() { return id; }
    public String getDocNo() { return docNo; }
    public LocalDate getPlanDate() { return planDate; }
    public ArchiveStatus getStatus() { return status; }
    public String getRemark() { return remark; }
    public List<DemandPlanLine> getLines() { return Collections.unmodifiableList(lines); }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    // -------- AuditTarget --------

    @Override
    public Long auditTargetId() { return id; }

    @Override
    public String auditTargetCode() { return docNo; }

    @Override
    public String auditSummary() {
        return "DP docNo=" + docNo + " planDate=" + planDate
                + " status=" + status + " lines=" + lines.size();
    }
}
