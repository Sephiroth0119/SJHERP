package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 销售出库单（M3-T09，路线图 §5 销售线）。
 *
 * <p>引用某销售订单（{@link #salesOrderNo}）做部分发货：从某仓库（{@link #warehouseId}）
 * 出库。行项目（{@link SalesDeliveryLine}）逐行对应订单某行的一次发货数量。
 * 走 {@link BusinessDocument} 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；
 * DRAFT → CANCELLED）。
 *
 * <h2>过账（审核后）</h2>
 * 每行 {@code SALES_OUT} 组一批一次过账经库存唯一写入口（CLAUDE.md 原则 1），库存按移动加权
 * 扣减并算出 COGS 回填到出库行（{@link SalesDeliveryLine#getCogsAmount()}），同事务回写销售
 * 订单各行累计发货量。库存不足且负库存关闭（默认）时整批回滚（销售出库强校验库存）。
 *
 * <p>退货留 TODO（M4 统一做红字出库单）。流水不在本单据内产生——单据只管状态与数据，
 * 过账由 {@link SalesDeliveryService} 编排。
 */
public final class SalesDelivery extends BusinessDocument implements AuditTarget {

    /** 引用的销售订单号（部分发货针对它，存在性/审核校验在出库服务） */
    private final String salesOrderNo;

    /** 出库仓库 id（单据头固定）；存在性/启用校验在 app 入口层 */
    private final long warehouseId;

    /** 出库说明（可空） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变；COGS 过账后回填到各行） */
    private final List<SalesDeliveryLine> lines;

    private SalesDelivery(String docNo, String salesOrderNo, long warehouseId, String remark,
                          List<SalesDeliveryLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.salesOrderNo = Objects.requireNonNull(salesOrderNo, "关联销售订单号不能为空");
        this.warehouseId = warehouseId;
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建销售出库单（草稿）。
     *
     * @param docNo        单据号（SD-年月-序号，由 DocumentNumberGenerator 生成）
     * @param salesOrderNo 引用的销售订单号
     * @param warehouseId  出库仓库 id
     * @param remark       出库说明（可空）
     * @param lines        行项目（至少一行，行号在单据内唯一）
     * @param createdBy    创建人
     */
    public static SalesDelivery create(String docNo, String salesOrderNo, long warehouseId, String remark,
                                       List<SalesDeliveryLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "出库行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("销售出库单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(SalesDeliveryLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("销售出库单行号不能重复");
        }
        return new SalesDelivery(docNo, salesOrderNo, warehouseId, remark, lines, createdBy);
    }

    /**
     * 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。
     *
     * @param status 落库的单据状态
     */
    public static SalesDelivery restore(String docNo, String salesOrderNo, long warehouseId, String remark,
                                        DocumentStatus status, List<SalesDeliveryLine> lines, String createdBy) {
        SalesDelivery delivery = new SalesDelivery(docNo, salesOrderNo, warehouseId, remark, lines, createdBy);
        delivery.restoreStatus(status);
        return delivery;
    }

    /** 出库总成本 = 各行 COGS 之和（仅过账后有意义；未回填的行计 0） */
    public BigDecimal totalCogs() {
        return lines.stream()
                .map(line -> line.getCogsAmount() == null ? BigDecimal.ZERO : line.getCogsAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public String getSalesOrderNo() {
        return salesOrderNo;
    }

    public long getWarehouseId() {
        return warehouseId;
    }

    public String getRemark() {
        return remark;
    }

    /**
     * 回写本次开票数量到指定出库行（销售发票 M3-T10 过账时由 {@link SalesDeliveryService} 编排调用）。
     * 累加后已开票量不得超过发货量（跨发票累计校验，超量拒绝——防跨发票超额开票虚增应收，
     * CLAUDE.md 原则 2）。仅已过账（COMPLETED，已发货且已可开票）的出库单可回写。
     *
     * @param lineNo   出库行号
     * @param invoiced 本次开票数量（> 0，基本单位）
     */
    public void invoiceLine(int lineNo, BigDecimal invoiced) {
        if (getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalStateException("销售出库单[" + getDocNo() + "] 当前状态 " + getStatus()
                    + " 未过账，不可回写开票量");
        }
        lineByNo(lineNo).addInvoiced(invoiced);
    }

    private SalesDeliveryLine lineByNo(int lineNo) {
        return lines.stream().filter(l -> l.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "销售出库单[" + getDocNo() + "] 不存在行号 " + lineNo));
    }

    /** 行项目只读视图（不可变引用集合，防外部直接增删行；行对象本身仍是聚合内可变实体） */
    public List<SalesDeliveryLine> getLines() {
        return List.copyOf(lines);
    }

    // ---------------------------------------------------------------
    // AuditTarget（审计切面从 @Audited 写方法返回值提取目标标识与摘要）
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        return null;
    }

    @Override
    public String auditTargetCode() {
        return getDocNo();
    }

    @Override
    public String auditSummary() {
        return "关联订单=" + salesOrderNo + ", 仓库=" + warehouseId + ", 状态=" + getStatus()
                + ", 行数=" + lines.size() + ", 出库成本=" + totalCogs().toPlainString()
                + ", 说明=" + AuditTarget.text(remark);
    }
}
