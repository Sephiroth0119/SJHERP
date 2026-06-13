package com.sjherp.domain.collection;

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
 * 收款单（M4-T04b，路线图 §6）：登记从某客户收到一笔款项并分摊核销其若干笔应收账款。
 *
 * <p>收款单是核销引擎（M4-T03）的<b>生产触发器</b>：过账时同事务内逐行调核销引擎冲减应收子账
 * （借现金/银行、贷应收），核销与收现金是同一笔经济业务，故二者原子同事务（编排在 app 层
 * {@code CollectionReceiptAppService.post}，照 {@code PurchaseInvoiceAppService} 范式）。
 *
 * <p>走 {@link BusinessDocument} 状态机（DRAFT → APPROVED → EXECUTING → COMPLETED；DRAFT → CANCELLED）。
 * 单据头：单据号 {@link #docNo}（RCPT-年月-序号）、客户 {@link #customerId}、收入的资金账户
 * {@link #paymentAccountId}、收款日期 {@link #receiptDate}、说明 {@link #remark}、分摊行 {@link #lines}。
 *
 * <h2>状态语义</h2>
 * <ul>
 *   <li>DRAFT：草稿，可作废；</li>
 *   <li>APPROVED：审核后业务内容锁定；</li>
 *   <li>EXECUTING：过账中（app 层在此核销应收 + 生成现金侧凭证）；</li>
 *   <li>COMPLETED：过账完成，自此只可冲销（M4-T07）。</li>
 * </ul>
 *
 * <p>{@link #totalAmount()} = Σ 各行分摊金额，即本次收款额、应收冲减总额、现金侧凭证金额。
 * 建单校验：至少一行、行号唯一、各行分摊金额 > 0（由 {@link CollectionReceiptLine} 守门）。
 */
public final class CollectionReceipt extends BusinessDocument implements AuditTarget {

    /** 客户 id（与各分摊行引用的应收客户一致，由 app 层过账时校验） */
    private final long customerId;

    /** 收入的资金账户 id（payment_account.id，过账时取其 glAccountCode 作现金侧借方科目） */
    private final long paymentAccountId;

    /** 收款日期（业务日期，核销业务日与凭证日期基准） */
    private final LocalDate receiptDate;

    /** 收款说明（可空） */
    private final String remark;

    /** 分摊行（按行号有序，建单后行集合不变） */
    private final List<CollectionReceiptLine> lines;

    private CollectionReceipt(String docNo, long customerId, long paymentAccountId,
                             LocalDate receiptDate, String remark, List<CollectionReceiptLine> lines,
                             String createdBy) {
        super(docNo, createdBy);
        this.customerId = customerId;
        this.paymentAccountId = paymentAccountId;
        this.receiptDate = Objects.requireNonNull(receiptDate, "收款日期不能为空");
        this.remark = remark;
        this.lines = new ArrayList<>(lines);
    }

    /**
     * 新建收款单（草稿）。
     *
     * @param docNo            单据号（RCPT-年月-序号，由 DocumentNumberGenerator 生成）
     * @param customerId       客户 id
     * @param paymentAccountId 收入的资金账户 id
     * @param receiptDate      收款日期
     * @param remark           收款说明（可空）
     * @param lines            分摊行（至少一行，行号在单据内唯一）
     * @param createdBy        创建人
     */
    public static CollectionReceipt create(String docNo, long customerId, long paymentAccountId,
                                           LocalDate receiptDate, String remark,
                                           List<CollectionReceiptLine> lines, String createdBy) {
        Objects.requireNonNull(lines, "收款单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("收款单至少要有一行");
        }
        List<Integer> lineNos = lines.stream().map(CollectionReceiptLine::getLineNo).distinct().toList();
        if (lineNos.size() != lines.size()) {
            throw new IllegalArgumentException("收款单行号不能重复");
        }
        // 同笔应收不得在单内多行重复分摊（防语义混乱、便于 T07 红冲按 paymentDocNo 反查对账）
        List<Long> receivableIds = lines.stream().map(CollectionReceiptLine::getReceivableId)
                .distinct().toList();
        if (receivableIds.size() != lines.size()) {
            throw new IllegalArgumentException("收款单同一应收不能在多行重复分摊");
        }
        return new CollectionReceipt(docNo, customerId, paymentAccountId, receiptDate, remark, lines,
                createdBy);
    }

    /** 持久层重建工厂（不重跑业务校验）：用既有状态恢复单据。 */
    public static CollectionReceipt restore(String docNo, long customerId, long paymentAccountId,
                                            LocalDate receiptDate, String remark, DocumentStatus status,
                                            List<CollectionReceiptLine> lines, String createdBy) {
        CollectionReceipt receipt = new CollectionReceipt(docNo, customerId, paymentAccountId,
                receiptDate, remark, lines, createdBy);
        receipt.restoreStatus(status);
        return receipt;
    }

    public long getCustomerId() {
        return customerId;
    }

    public long getPaymentAccountId() {
        return paymentAccountId;
    }

    public LocalDate getReceiptDate() {
        return receiptDate;
    }

    public String getRemark() {
        return remark;
    }

    /** 分摊行只读视图（不可变，防外部直接增删行） */
    public List<CollectionReceiptLine> getLines() {
        return List.copyOf(lines);
    }

    /** 收款总额（各行分摊金额之和，2 位小数；即应收冲减总额与现金侧凭证金额） */
    public BigDecimal totalAmount() {
        return lines.stream().map(CollectionReceiptLine::getAllocatedAmount)
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
        return "客户=" + customerId + ", 资金账户=" + paymentAccountId + ", 收款日期=" + receiptDate
                + ", 状态=" + getStatus() + ", 行数=" + lines.size()
                + ", 总金额=" + totalAmount().toPlainString();
    }
}
