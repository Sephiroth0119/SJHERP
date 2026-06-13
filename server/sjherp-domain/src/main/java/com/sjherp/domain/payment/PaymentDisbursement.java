package com.sjherp.domain.payment;

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
 * 付款单（M4-T04b，路线图 §6）：登记向某供应商付出一笔款项并分摊核销其若干笔应付账款。
 *
 * <p>付款单与收款单 {@code CollectionReceipt} 对称，是核销引擎（M4-T03）的<b>生产触发器</b>：
 * 过账时同事务内逐行调核销引擎冲减应付子账（借应付、贷现金/银行），核销与付现金是同一笔经济
 * 业务，故二者原子同事务（编排在 app 层 {@code PaymentDisbursementAppService.post}，照
 * {@code PurchaseInvoiceAppService} / {@code CollectionReceiptAppService} 范式）。
 *
 * <p>走 {@link BusinessDocument} 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED）。
 * 单据头：单据号 {@link #docNo}（PAYV-年月-序号）、供应商 {@link #supplierId}、付出的资金账户
 * {@link #paymentAccountId}、付款日期 {@link #paymentDate}、说明 {@link #remark}、分摊行 {@link #lines}。
 *
 * <h2>状态语义</h2>
 * <ul>
 *   <li>DRAFT：草稿，可作废；</li>
 *   <li>APPROVED：审核后业务内容锁定；</li>
 *   <li>EXECUTING：过账中（app 层在此核销应付 + 生成现金侧凭证）；</li>
 *   <li>COMPLETED：过账完成，自此只可冲销（M4-T07）。</li>
 * </ul>
 *
 * <p>{@link #totalAmount()} = Σ 各行分摊金额，即本次付款额、应付冲减总额、现金侧凭证金额。
 * 建单校验：至少一行、行号唯一、各行分摊金额 > 0（由 {@link PaymentDisbursementLine} 守门）。
 */
public final class PaymentDisbursement extends BusinessDocument implements AuditTarget {

    /** 供应商 id（与各分摊行引用的应付供应商一致，由 app 层过账时校验） */
    private final long supplierId;

    /** 付出的资金账户 id（payment_account.id，过账时取其 glAccountCode 作现金侧贷方科目） */
    private final long paymentAccountId;

    /** 付款日期（业务日期，核销业务日与凭证日期基准） */
    private final LocalDate paymentDate;

    /** 付款说明（可空） */
    private final String remark;

    /** 分摊行（按行号有序，建单后行集合不变） */
    private final List<PaymentDisbursementLine> lines;

    private PaymentDisbursement(String docNo, long supplierId, long paymentAccountId,
                               LocalDate paymentDate, String remark, List<PaymentDisbursementLine> lines,
                               String createdBy) {
        super(docNo, createdBy);
        this.supplierId = supplierId;
        this.paymentAccountId = paymentAccountId;
        this.paymentDate = Objects.requireNonNull(paymentDate, "付款日期不能为空");
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建付款单（草稿）。
     *
     * @param docNo            单据号（PAYV-年月-序号，由 DocumentNumberGenerator 生成）
     * @param supplierId       供应商 id
     * @param paymentAccountId 付出的资金账户 id
     * @param paymentDate      付款日期
     * @param remark           付款说明（可空）
     * @param lines            分摊行（至少一行，行号在单据内唯一）
     * @param createdBy        创建人
     */
    public static PaymentDisbursement create(String docNo, long supplierId, long paymentAccountId,
                                             LocalDate paymentDate, String remark,
                                             List<PaymentDisbursementLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "付款单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("付款单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(PaymentDisbursementLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("付款单行号不能重复");
        }
        // 同笔应付不得在单内多行重复分摊（防语义混乱、便于 T07 红冲按 paymentDocNo 反查对账）
        List<Long> payableIds = lines.stream().map(PaymentDisbursementLine::getPayableId)
                .distinct().toList();
        if (payableIds.size() != lines.size()) {
            throw new IllegalArgumentException("付款单同一应付不能在多行重复分摊");
        }
        return new PaymentDisbursement(docNo, supplierId, paymentAccountId, paymentDate, remark, lines,
                createdBy);
    }

    /** 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。 */
    public static PaymentDisbursement restore(String docNo, long supplierId, long paymentAccountId,
                                              LocalDate paymentDate, String remark, DocumentStatus status,
                                              List<PaymentDisbursementLine> lines, String createdBy) {
        PaymentDisbursement disbursement = new PaymentDisbursement(docNo, supplierId, paymentAccountId,
                paymentDate, remark, lines, createdBy);
        disbursement.restoreStatus(status);
        return disbursement;
    }

    public long getSupplierId() {
        return supplierId;
    }

    public long getPaymentAccountId() {
        return paymentAccountId;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public String getRemark() {
        return remark;
    }

    /** 分摊行只读视图（不可变，防外部直接增删行） */
    public List<PaymentDisbursementLine> getLines() {
        return List.copyOf(lines);
    }

    /** 付款总额（各行分摊金额之和，2 位小数；即应付冲减总额与现金侧凭证金额） */
    public BigDecimal totalAmount() {
        return lines.stream().map(PaymentDisbursementLine::getAllocatedAmount)
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
        return "供应商=" + supplierId + ", 资金账户=" + paymentAccountId + ", 付款日期=" + paymentDate
                + ", 状态=" + getStatus() + ", 行数=" + lines.size()
                + ", 总金额=" + totalAmount().toPlainString();
    }
}
