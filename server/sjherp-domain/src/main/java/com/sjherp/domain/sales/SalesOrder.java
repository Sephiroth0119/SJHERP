package com.sjherp.domain.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.inventory.CostingStrategy;
import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 销售订单（M3-T08，路线图 §5 销售线）。
 *
 * <p>单据头固定一个 {@link #customerId 客户}与一个 {@link #orderDate 订单日期}，
 * 行项目（{@link SalesOrderLine}）是逐商品的销售数量与单价。走 {@link BusinessDocument}
 * 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED）。
 *
 * <h2>状态语义</h2>
 * <ul>
 *   <li>DRAFT：草稿，可作废；</li>
 *   <li>APPROVED：审核后业务内容锁定，自此可据其开销售出库单（M3-T09）发货；</li>
 *   <li>EXECUTING：执行中（部分发货后由销售出库单驱动推进，v1.0 简化：订单完成由人工/出库
 *       全部发完后推进，本聚合提供 {@link #startExecution}/{@link #complete}）；</li>
 *   <li>COMPLETED：订单完成。</li>
 * </ul>
 *
 * <h2>库存语义（重要）</h2>
 * <b>下单不动库存</b>——销售订单只是销售约定，不产生库存流水。可用库存检查在 app 入口层做
 * 「下单时查可用量不足仅警告不阻断」（默认提示，可配置），真正的库存扣减发生在销售出库单过账
 * （SALES_OUT，经库存唯一写入口，CLAUDE.md 原则 1）。
 *
 * <p>累计发货量记在各行 {@link SalesOrderLine#getDeliveredQty()}，由出库单过账回写。
 */
public final class SalesOrder extends BusinessDocument implements AuditTarget {

    /** 客户 id（单据头固定）；存在性/启用校验在 app 入口层 */
    private final long customerId;

    /** 订单日期 */
    private final LocalDate orderDate;

    /** 订单说明（可空） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变） */
    private final List<SalesOrderLine> lines;

    private SalesOrder(String docNo, long customerId, LocalDate orderDate, String remark,
                       List<SalesOrderLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.customerId = customerId;
        this.orderDate = Objects.requireNonNull(orderDate, "订单日期不能为空");
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建销售订单（草稿）。
     *
     * @param docNo      单据号（SO-年月-序号，由 DocumentNumberGenerator 生成）
     * @param customerId 客户 id
     * @param orderDate  订单日期
     * @param remark     订单说明（可空）
     * @param lines      行项目（至少一行，行号在单据内唯一）
     * @param createdBy  创建人
     */
    public static SalesOrder create(String docNo, long customerId, LocalDate orderDate, String remark,
                                    List<SalesOrderLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "订单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("销售订单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(SalesOrderLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("销售订单行号不能重复");
        }
        return new SalesOrder(docNo, customerId, orderDate, remark, lines, createdBy);
    }

    /**
     * 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。
     *
     * @param status 落库的单据状态
     */
    public static SalesOrder restore(String docNo, long customerId, LocalDate orderDate, String remark,
                                     DocumentStatus status, List<SalesOrderLine> lines, String createdBy) {
        SalesOrder order = new SalesOrder(docNo, customerId, orderDate, remark, lines, createdBy);
        order.restoreStatus(status);
        return order;
    }

    /** 按行号取行（不存在抛 IllegalArgumentException）——出库过账回写累计发货量用 */
    public SalesOrderLine lineByNo(int lineNo) {
        return lines.stream().filter(line -> line.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("销售订单[" + getDocNo()
                        + "] 不存在行号 " + lineNo));
    }

    /** 订单总金额 = 各行金额之和（2 位 HALF_UP） */
    public BigDecimal totalAmount() {
        return lines.stream().map(SalesOrderLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    public long getCustomerId() {
        return customerId;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public String getRemark() {
        return remark;
    }

    /** 行项目只读视图（不可变引用集合，防外部直接增删行；行对象本身仍是聚合内可变实体） */
    public List<SalesOrderLine> getLines() {
        return List.copyOf(lines);
    }

    // ---------------------------------------------------------------
    // AuditTarget（审计切面从 @Audited 写方法返回值提取目标标识与摘要）
    // ---------------------------------------------------------------

    @Override
    public Long auditTargetId() {
        // 销售订单无数据库自增 id 暴露（聚合以单据号为业务键），统一返回 null
        return null;
    }

    @Override
    public String auditTargetCode() {
        return getDocNo();
    }

    @Override
    public String auditSummary() {
        return "客户=" + customerId + ", 订单日期=" + orderDate + ", 状态=" + getStatus()
                + ", 行数=" + lines.size() + ", 金额=" + totalAmount().toPlainString()
                + ", 说明=" + AuditTarget.text(remark);
    }
}
