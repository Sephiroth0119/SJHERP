package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 工单聚合根（M5-T03）。
 *
 * <p>状态机复用 {@link DocumentStatus} 六态，通过 {@link #beforeTransition} 收紧白名单：
 * <ul>
 *   <li>计划(DRAFT) → 下达(APPROVED) = release()</li>
 *   <li>计划(DRAFT) → 作废(CANCELLED) = cancel()</li>
 *   <li>下达(APPROVED) → 开工(EXECUTING) = start()</li>
 *   <li>开工(EXECUTING) → 完工(COMPLETED) = complete()</li>
 *   <li>下达(APPROVED) → 冲销(REVERSED) = reverse()（未投产可冲销）</li>
 *   <li>其余流转全部否决（特别是 EXECUTING/COMPLETED→REVERSED，待 T04/T05 副作用反向后再放开）</li>
 * </ul>
 *
 * <p>本批冲销不动库存、不出凭证（无副作用）；completedQty/bomVersion/routingVersion/warehouseId/计划日期为预留字段。
 */
public class WorkOrder extends BusinessDocument implements AuditTarget {

    /** 数据库自增主键（建单时为 null，仓储回填后有值） */
    private Long id;

    /** 生产商品 id（FK product.id） */
    private final long productId;

    /** 计划生产数量（>0，BigDecimal/DECIMAL(18,6)） */
    private final BigDecimal plannedQty;

    /** 计量单位 id（FK unit.id） */
    private final long unitId;

    /** 已完工数量（预留，初始为 0，T05 完工入库时更新） */
    private BigDecimal completedQty;

    /** 引用的 BOM 版本号（预留，可为 null） */
    private final Integer bomVersion;

    /** 引用的工艺路线版本号（预留，可为 null） */
    private final Integer routingVersion;

    /** 生产仓库 id（预留，可为 null） */
    private final Long warehouseId;

    /** 来源 MRP 运行单号（仅 MRP_SUGGESTION 来源时有值） */
    private final String mrpRunDocNo;

    /** 来源类型 */
    private final WorkOrderSourceType sourceType;

    /** 计划开始日期（预留，可为 null） */
    private final LocalDate plannedStartDate;

    /** 计划结束日期（预留，可为 null） */
    private final LocalDate plannedEndDate;

    /** 备注（可为 null） */
    private final String remark;

    // ---------------------------------------------------------------- 私有构造（仅供工厂方法调用）

    private WorkOrder(
            String docNo,
            long productId,
            BigDecimal plannedQty,
            long unitId,
            BigDecimal completedQty,
            Integer bomVersion,
            Integer routingVersion,
            Long warehouseId,
            String mrpRunDocNo,
            WorkOrderSourceType sourceType,
            LocalDate plannedStartDate,
            LocalDate plannedEndDate,
            String remark,
            String createdBy) {
        super(docNo, createdBy);
        this.productId = productId;
        this.plannedQty = Objects.requireNonNull(plannedQty, "计划数量不能为空");
        this.unitId = unitId;
        this.completedQty = completedQty != null ? completedQty : BigDecimal.ZERO;
        this.bomVersion = bomVersion;
        this.routingVersion = routingVersion;
        this.warehouseId = warehouseId;
        this.mrpRunDocNo = mrpRunDocNo;
        this.sourceType = Objects.requireNonNull(sourceType, "来源类型不能为空");
        this.plannedStartDate = plannedStartDate;
        this.plannedEndDate = plannedEndDate;
        this.remark = remark;
    }

    // ---------------------------------------------------------------- 工厂方法

    /**
     * 手工建单（DRAFT 初态）。
     *
     * @param docNo            系统生成单号（WO- 前缀）
     * @param productId        生产商品 id
     * @param plannedQty       计划数量（必须 > 0）
     * @param unitId           计量单位 id
     * @param bomVersion       BOM 版本（预留，可为 null）
     * @param routingVersion   工艺路线版本（预留，可为 null）
     * @param warehouseId      生产仓库（预留，可为 null）
     * @param plannedStartDate 计划开始日期（可为 null）
     * @param plannedEndDate   计划结束日期（可为 null）
     * @param remark           备注（可为 null）
     * @param operator         操作人（审计）
     * @return 新建 DRAFT 工单
     */
    public static WorkOrder create(
            String docNo,
            long productId,
            BigDecimal plannedQty,
            long unitId,
            Integer bomVersion,
            Integer routingVersion,
            Long warehouseId,
            LocalDate plannedStartDate,
            LocalDate plannedEndDate,
            String remark,
            String operator) {
        if (plannedQty == null || plannedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("计划数量必须大于 0");
        }
        return new WorkOrder(docNo, productId, plannedQty, unitId,
                BigDecimal.ZERO,
                bomVersion, routingVersion, warehouseId,
                null, WorkOrderSourceType.MANUAL,
                plannedStartDate, plannedEndDate, remark,
                operator);
    }

    /**
     * 从 MRP 生产建议建单（DRAFT 初态）。
     *
     * @param docNo       系统生成单号（WO- 前缀）
     * @param productId   生产商品 id
     * @param plannedQty  净需求量（来自 MrpSuggestion.netRequirement，必须 > 0）
     * @param unitId      基本单位 id（来自 MrpSuggestion.baseUnitId）
     * @param mrpRunDocNo 来源 MRP 运行单号
     * @param operator    操作人（审计）
     * @return 新建 DRAFT 工单（来源 MRP_SUGGESTION）
     */
    public static WorkOrder createFromSuggestion(
            String docNo,
            long productId,
            BigDecimal plannedQty,
            long unitId,
            String mrpRunDocNo,
            String operator) {
        if (plannedQty == null || plannedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("MRP 净需求量必须大于 0，无法建单");
        }
        return new WorkOrder(docNo, productId, plannedQty, unitId,
                BigDecimal.ZERO,
                null, null, null,
                mrpRunDocNo, WorkOrderSourceType.MRP_SUGGESTION,
                null, null, null,
                operator);
    }

    /**
     * 从数据库还原工单（不做业务校验、不发事件）。仅供仓储层调用。
     *
     * <p>注：{@link BusinessDocument} 基类的 reversalOfId/reversedById 字段
     * 通过 {@link #restoreStatus} 之外无公开还原 API，维持与其他领域对象一致的 restore 范式。
     */
    public static WorkOrder restore(
            long id,
            String docNo,
            long productId,
            BigDecimal plannedQty,
            long unitId,
            BigDecimal completedQty,
            Integer bomVersion,
            Integer routingVersion,
            Long warehouseId,
            String mrpRunDocNo,
            WorkOrderSourceType sourceType,
            LocalDate plannedStartDate,
            LocalDate plannedEndDate,
            String remark,
            DocumentStatus status,
            String createdBy) {
        WorkOrder wo = new WorkOrder(docNo, productId, plannedQty, unitId,
                completedQty,
                bomVersion, routingVersion, warehouseId,
                mrpRunDocNo, sourceType,
                plannedStartDate, plannedEndDate, remark,
                createdBy);
        wo.id = id;
        wo.restoreStatus(status);
        return wo;
    }

    /** 数据库自增主键回填（仅供仓储层调用） */
    public void assignId(long id) {
        this.id = id;
    }

    // ---------------------------------------------------------------- 业务状态流转

    /**
     * 下达工单（DRAFT → APPROVED）。工单下达后进入可开工状态。
     *
     * @param operator 操作人（审计）
     */
    public void release(String operator) {
        approve(operator);
    }

    /**
     * 开工（APPROVED → EXECUTING）。工单进入生产执行阶段。
     *
     * @param operator 操作人（审计）
     */
    public void start(String operator) {
        startExecution(operator);
    }

    /**
     * 完工（EXECUTING → COMPLETED）。
     *
     * @param operator 操作人（审计）
     */
    @Override
    public void complete(String operator) {
        super.complete(operator);
    }

    /**
     * 累加完工数量（T05 报工过账时调用，每张报工单完工入库后回写一次）。
     *
     * @param qty      本次完工入库数量（必须 > 0）
     * @param operator 操作人（审计用，预留扩展）
     */
    public void recordCompletion(BigDecimal qty, String operator) {
        Objects.requireNonNull(qty, "完工数量不能为空");
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("完工数量必须大于 0，实际: " + qty.toPlainString());
        }
        this.completedQty = this.completedQty.add(qty);
    }

    /**
     * 冲销（仅 APPROVED → REVERSED，本批不动库存、不出凭证）。
     * EXECUTING/COMPLETED 状态的冲销因有 T04/T05 实物副作用，待后续任务放开，本批否决。
     *
     * @param operator 操作人（审计）
     */
    public void reverse(String operator) {
        // 无独立红字单，传自身 docNo 作冲销关联标记
        super.reverse(operator, getDocNo());
    }

    // ---------------------------------------------------------------- 状态机白名单（收紧基类允许范围）

    /**
     * 覆写白名单——WO 仅允许：
     * DRAFT→APPROVED（下达）、DRAFT→CANCELLED（作废）、
     * APPROVED→EXECUTING（开工）、EXECUTING→COMPLETED（完工）、
     * APPROVED→REVERSED（未投产冲销）。
     * 其余一律否决（基类允许的 EXECUTING/COMPLETED→REVERSED 在此被否决）。
     */
    @Override
    protected void beforeTransition(DocumentStatus from, DocumentStatus to, String operator) {
        boolean allowed =
                (from == DocumentStatus.DRAFT    && to == DocumentStatus.APPROVED)
             || (from == DocumentStatus.DRAFT    && to == DocumentStatus.CANCELLED)
             || (from == DocumentStatus.APPROVED && to == DocumentStatus.EXECUTING)
             || (from == DocumentStatus.EXECUTING && to == DocumentStatus.COMPLETED)
             || (from == DocumentStatus.APPROVED  && to == DocumentStatus.REVERSED);
        if (!allowed) {
            throw new IllegalStateTransitionException(getDocNo(), from, to);
        }
    }

    // ---------------------------------------------------------------- AuditTarget 实现

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return getDocNo();
    }

    @Override
    public String auditSummary() {
        return "工单[" + getDocNo() + "] 商品id=" + productId + " 计划量=" + plannedQty.toPlainString();
    }

    // ---------------------------------------------------------------- Getter

    public Long getId() { return id; }
    public long getProductId() { return productId; }
    public BigDecimal getPlannedQty() { return plannedQty; }
    public long getUnitId() { return unitId; }
    public BigDecimal getCompletedQty() { return completedQty; }
    public Integer getBomVersion() { return bomVersion; }
    public Integer getRoutingVersion() { return routingVersion; }
    public Long getWarehouseId() { return warehouseId; }
    public String getMrpRunDocNo() { return mrpRunDocNo; }
    public WorkOrderSourceType getSourceType() { return sourceType; }
    public LocalDate getPlannedStartDate() { return plannedStartDate; }
    public LocalDate getPlannedEndDate() { return plannedEndDate; }
    public String getRemark() { return remark; }
}
