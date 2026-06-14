package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.BusinessDocument;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.audit.AuditTarget;
import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 采购订单（M3-T05，路线图 §5 采购线）。
 *
 * <p>对供应商的采购承诺：单据头固定一个供应商 {@link #supplierId} 与下单日期 {@link #orderDate}，
 * 行项目（{@link PurchaseOrderLine}）逐商品记订购数量、采购单价与累计到货量。走
 * {@link BusinessDocument} 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED）。
 *
 * <h2>状态语义（本单据收紧规则）</h2>
 * <ul>
 *   <li>DRAFT：草稿，可作废；</li>
 *   <li>APPROVED：审核后业务内容锁定，<b>自此可被采购入库单引用收货</b>（部分收货跟踪在行上）；</li>
 *   <li>EXECUTING / COMPLETED：关闭采购订单（收货完成或主动关闭）后置 COMPLETED，自此不再收货；</li>
 *   <li>CANCELLED：草稿作废（未产生任何承诺影响）。</li>
 * </ul>
 *
 * <p><b>下单不动库存</b>：采购订单只是承诺，库存只在采购入库单（M3-T06）过账时经库存唯一写入口
 * 产生（CLAUDE.md 原则 1）。到货数量由采购入库单过账时回写到行的 {@link PurchaseOrderLine#getReceivedQty}。
 *
 * <h2>核心约束（建单时强制）</h2>
 * 至少一行，行号唯一、数量 > 0、单价 ≥ 0（由 {@link PurchaseOrderLine} 守门）。
 */
public final class PurchaseOrder extends BusinessDocument implements AuditTarget {

    /** 供应商 id（单据头固定）；存在性/启用校验在 app 入口层 */
    private final long supplierId;

    /** 下单日期（业务日期，可与建单系统时间不同） */
    private final LocalDate orderDate;

    /** 采购说明（可空） */
    private final String remark;

    /** 行项目（按行号有序，建单后行集合不变；行内 receivedQty 随收货累加） */
    private final List<PurchaseOrderLine> lines;

    private PurchaseOrder(String docNo, long supplierId, LocalDate orderDate, String remark,
                          List<PurchaseOrderLine> lines, String createdBy) {
        super(docNo, createdBy);
        this.supplierId = supplierId;
        this.orderDate = Objects.requireNonNull(orderDate, "下单日期不能为空");
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建采购订单（草稿）。
     *
     * @param docNo      单据号（PO-年月-序号，由 DocumentNumberGenerator 生成）
     * @param supplierId 供应商 id
     * @param orderDate  下单日期
     * @param remark     采购说明（可空）
     * @param lines      行项目（至少一行，行号在单据内唯一）
     * @param createdBy  创建人
     */
    public static PurchaseOrder create(String docNo, long supplierId, LocalDate orderDate, String remark,
                                       List<PurchaseOrderLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "采购订单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("采购订单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(PurchaseOrderLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("采购订单行号不能重复");
        }
        return new PurchaseOrder(docNo, supplierId, orderDate, remark, lines, createdBy);
    }

    /** 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。 */
    public static PurchaseOrder restore(String docNo, long supplierId, LocalDate orderDate, String remark,
                                        DocumentStatus status, List<PurchaseOrderLine> lines,
                                        String createdBy) {
        PurchaseOrder order = new PurchaseOrder(docNo, supplierId, orderDate, remark, lines, createdBy);
        order.restoreStatus(status);
        return order;
    }

    /**
     * 收货时回写某行的累计到货量（采购入库单 M3-T06 过账时在同一外层事务内调用）。
     *
     * <p>只允许在订单已审核（APPROVED 或之后但未关闭）时收货；累计到货量不得超过订购量
     * （部分收货校验在行上，超量拒绝整批回滚）。
     *
     * @param lineNo   被收货的采购订单行号
     * @param received 本次到货数量（> 0，基本单位）
     */
    public void receiveLine(int lineNo, BigDecimal received) {
        if (getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalStateException("采购订单[" + getDocNo() + "] 当前状态 " + getStatus()
                    + " 不可收货（仅已审核且未关闭的订单可收货）");
        }
        PurchaseOrderLine line = lineByNo(lineNo);
        line.addReceived(received);
    }

    /**
     * 回滚某行累计到货量（M4-T07b 采购入库红冲时在同一外层事务内调用）。
     *
     * <p>红冲发生在订单仍可收货（APPROVED）期间，与 {@link #receiveLine} 同状态约束；
     * 回滚后已到货量不得 &lt; 0（由 {@link PurchaseOrderLine#subtractReceived} 守门）。
     *
     * @param lineNo   被回滚的采购订单行号
     * @param received 本次回滚的到货数量（&gt; 0，基本单位）
     */
    public void reverseReceiveLine(int lineNo, BigDecimal received) {
        if (getStatus() != DocumentStatus.APPROVED) {
            throw new IllegalStateException("采购订单[" + getDocNo() + "] 当前状态 " + getStatus()
                    + " 不可回滚到货量（仅已审核且未关闭的订单可回滚）");
        }
        lineByNo(lineNo).subtractReceived(received);
    }

    /**
     * 关闭采购订单：APPROVED → EXECUTING → COMPLETED，自此不再收货。
     *
     * <p>EXECUTING 是 BusinessDocument 流转表的中转态（APPROVED 不能直接 → COMPLETED），
     * 关闭一步走完两段，对外语义即「订单已关闭」。
     */
    public void close(String operator) {
        startExecution(operator);
        complete(operator);
    }

    private PurchaseOrderLine lineByNo(int lineNo) {
        return lines.stream().filter(l -> l.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "采购订单[" + getDocNo() + "] 不存在行号 " + lineNo));
    }

    public long getSupplierId() {
        return supplierId;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public String getRemark() {
        return remark;
    }

    /** 行项目只读视图（不可变，防外部直接增删行） */
    public List<PurchaseOrderLine> getLines() {
        return List.copyOf(lines);
    }

    /** 订单总金额（各行金额之和，2 位小数） */
    public BigDecimal totalAmount() {
        return lines.stream().map(PurchaseOrderLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CostingStrategy.AMOUNT_SCALE, CostingStrategy.ROUNDING);
    }

    // ---------------------------------------------------------------
    // AuditTarget
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
        return "供应商=" + supplierId + ", 下单日期=" + orderDate + ", 状态=" + getStatus()
                + ", 行数=" + lines.size() + ", 总金额=" + totalAmount().toPlainString()
                + ", 说明=" + AuditTarget.text(remark);
    }
}
