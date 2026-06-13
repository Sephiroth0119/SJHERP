package com.sjherp.domain.payment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 付款单领域服务（M4-T04b，路线图 §6）：所有付款单写操作的唯一入口（CLAUDE.md 原则 1）。
 *
 * <p>纯 Java 零依赖：仅依赖付款单仓储端口 {@link PaymentDisbursementRepository} 与领域事件发布器
 * {@link DomainEventPublisher}，由 app 层装配并把单据状态变更 + 跨聚合编排（核销 + 现金侧凭证）
 * 包进同一外层事务。
 *
 * <h2>职责边界（关键约束，照 CollectionReceiptService / PurchaseInvoiceService）</h2>
 * 本领域服务<b>只负责付款单自身的状态机推进与持久化</b>——{@link #post} 仅把单据推到 COMPLETED，
 * <b>不直接碰应付子账 / 核销引擎 / GL 凭证</b>。跨聚合编排（逐行核销应付、生成现金侧凭证、对手方
 * 一致性校验）放在 app 层 {@code PaymentDisbursementAppService.post} 的同一 @Transactional 内，
 * 任一失败整单回滚（资金/核销/GL/单据状态不半生效，设计真源 §2.3）。
 *
 * <p>幂等：单据状态机（COMPLETED 不可重过账）为主防线，核销记录 paymentDocNo=单号、
 * 现金侧凭证 findBySourceDocNo 查重为纵深（均在 app 编排层）。
 */
public class PaymentDisbursementService {

    private final PaymentDisbursementRepository repository;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计 */
    private final DomainEventPublisher eventPublisher;

    public PaymentDisbursementService(PaymentDisbursementRepository repository,
                                      DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建付款单（草稿）：把本次付款分摊到若干笔应付，自动 PAYV- 编号（app 层生成传入）。
     *
     * @param docNo            单据号（PAYV-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param supplierId       供应商 id
     * @param paymentAccountId 付出的资金账户 id
     * @param paymentDate      付款日期
     * @param remark           付款说明（可空）
     * @param lines            分摊行输入（分摊到的应付主键 + 分摊金额）
     * @param operator         操作人
     */
    @Audited(action = "payment_disbursement.create", targetType = "payment_disbursement")
    public PaymentDisbursement create(String docNo, long supplierId, long paymentAccountId,
                                      LocalDate paymentDate, String remark,
                                      List<PaymentDisbursementLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(paymentDate, "付款日期不能为空");
        Objects.requireNonNull(lines, "付款单行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("付款单至少要有一行");
        }

        List<PaymentDisbursementLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (PaymentDisbursementLineInput input : lines) {
            Objects.requireNonNull(input, "付款单行输入不能为空");
            domainLines.add(PaymentDisbursementLine.create(lineNo++, input.payableId(),
                    input.allocatedAmount()));
        }

        PaymentDisbursement disbursement = PaymentDisbursement.create(docNo, supplierId,
                paymentAccountId, paymentDate, remark, domainLines, operator);
        disbursement.registerEventPublisher(eventPublisher);
        repository.save(disbursement);
        return disbursement;
    }

    /** 审核付款单：DRAFT → APPROVED（业务内容自此锁定）。 */
    @Audited(action = "payment_disbursement.approve", targetType = "payment_disbursement")
    public PaymentDisbursement approve(String docNo, String operator) {
        requireOperator(operator);
        PaymentDisbursement disbursement = get(docNo);
        disbursement.registerEventPublisher(eventPublisher);
        disbursement.approve(operator);
        repository.save(disbursement);
        return disbursement;
    }

    /**
     * 过账付款单：APPROVED → EXECUTING → COMPLETED（仅推进单据状态机）。
     *
     * <p>核销应付 + 现金侧凭证由 app 层编排在同一外层事务内（本方法之后调用），见类注释职责边界。
     */
    @Audited(action = "payment_disbursement.post", targetType = "payment_disbursement")
    public PaymentDisbursement post(String docNo, String operator) {
        requireOperator(operator);
        PaymentDisbursement disbursement = get(docNo);
        disbursement.registerEventPublisher(eventPublisher);
        disbursement.startExecution(operator);
        disbursement.complete(operator);
        repository.save(disbursement);
        return disbursement;
    }

    /** 按单据号查（不存在抛 {@link PaymentDisbursementNotFoundException} → API 404） */
    public PaymentDisbursement get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new PaymentDisbursementNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<PaymentDisbursement> search(PaymentDisbursementQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
