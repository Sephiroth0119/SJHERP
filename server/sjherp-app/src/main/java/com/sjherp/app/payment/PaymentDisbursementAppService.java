package com.sjherp.app.payment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.payment.PaymentDtos.PaymentDisbursementLineRequest;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payable.PayableNotFoundException;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementLine;
import com.sjherp.domain.payment.PaymentDisbursementLineInput;
import com.sjherp.domain.payment.PaymentDisbursementQuery;
import com.sjherp.domain.payment.PaymentDisbursementService;
import com.sjherp.domain.settlement.SettlementService;

/**
 * 付款单应用服务（M4-T04b）：REST {@code PaymentDisbursementController} 与 Agent 工具的公共入口。
 *
 * <p>与收款单 {@code CollectionReceiptAppService} 对称。职责（照 {@code PurchaseInvoiceAppService}）：
 * <ul>
 *   <li>建单：自动 PAYV- 编号 → 调领域 {@link PaymentDisbursementService#create}；</li>
 *   <li>审核：直接委托领域服务；</li>
 *   <li><b>过账（验收核心，设计真源 §2.3）</b>：在<b>同一 {@code @Transactional}</b> 内——
 *       (1) 推进单据状态机至 COMPLETED；(2) 取资金账户拿 glAccountCode；
 *       (3) 逐行核销应付（先校验应付供应商 == 单据供应商，再经核销引擎冲减应付子账）；
 *       (4) 生成现金侧凭证（借 220202 应付、贷 glAccountCode 现金/银行）。
 *       任一失败整单回滚（资金/核销/GL/单据状态不半生效）；</li>
 *   <li>查询：直接委托领域服务。</li>
 * </ul>
 *
 * <p>对手方一致性：每行的应付供应商必须等于付款单供应商，防跨供应商误核销（设计真源 §6.3）。
 * 超额核销由核销引擎（{@code AccountsPayable.settle}）硬拒（OverSettlementException）→ 整单回滚
 * （不能付超过欠款）。
 */
@Service
public class PaymentDisbursementAppService {

    /** 付款单编号规则：PAYV-202606-0001 */
    static final DocumentNumberRule PAYMENT_DISBURSEMENT_RULE = DocumentNumberRule.of("PAYV");

    private final PaymentDisbursementService paymentDisbursementService;
    private final PaymentAccountService paymentAccountService;
    private final AccountsPayableRepository payableRepository;
    private final SettlementService settlementService;
    private final AutoVoucherService autoVoucherService;
    private final DocumentNumberGenerator numberGenerator;

    public PaymentDisbursementAppService(PaymentDisbursementService paymentDisbursementService,
                                         PaymentAccountService paymentAccountService,
                                         AccountsPayableRepository payableRepository,
                                         SettlementService settlementService,
                                         AutoVoucherService autoVoucherService,
                                         DocumentNumberGenerator numberGenerator) {
        this.paymentDisbursementService = Objects.requireNonNull(paymentDisbursementService,
                "paymentDisbursementService 不能为空");
        this.paymentAccountService = Objects.requireNonNull(paymentAccountService,
                "paymentAccountService 不能为空");
        this.payableRepository = Objects.requireNonNull(payableRepository,
                "payableRepository 不能为空");
        this.settlementService = Objects.requireNonNull(settlementService,
                "settlementService 不能为空");
        this.autoVoucherService = Objects.requireNonNull(autoVoucherService,
                "autoVoucherService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 创建付款单（草稿）：分摊本次付款到若干笔应付，自动 PAYV- 编号。
     *
     * @param supplierId       供应商 id
     * @param paymentAccountId 付出的资金账户 id
     * @param paymentDate      付款日期（为空时默认今天）
     * @param remark           付款说明（可空）
     * @param lines            分摊行（应付 id + 分摊金额）
     * @param operator         操作人
     */
    @Transactional
    public PaymentDisbursement create(long supplierId, long paymentAccountId, LocalDate paymentDate,
                                      String remark, List<PaymentDisbursementLineRequest> lines,
                                      String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("付款单至少要有一行");
        }
        List<PaymentDisbursementLineInput> domainLines = new ArrayList<>(lines.size());
        for (PaymentDisbursementLineRequest input : lines) {
            if (input.payableId() == null) {
                throw new IllegalArgumentException("分摊行引用的应付账款 id 不能为空");
            }
            domainLines.add(new PaymentDisbursementLineInput(input.payableId(),
                    input.allocatedAmount()));
        }
        LocalDate effectiveDate = paymentDate != null ? paymentDate : LocalDate.now();
        String docNo = numberGenerator.generate(PAYMENT_DISBURSEMENT_RULE);
        return paymentDisbursementService.create(docNo, supplierId, paymentAccountId, effectiveDate,
                remark, domainLines, operator);
    }

    /** 审核付款单（DRAFT → APPROVED） */
    @Transactional
    public PaymentDisbursement approve(String docNo, String operator) {
        return paymentDisbursementService.approve(docNo, operator);
    }

    /**
     * 过账付款单（验收核心）：同一事务内推进状态机 → 逐行核销应付 → 生成现金侧凭证。
     *
     * <p>原子性（设计真源 §2.3）：(1) 单据 →COMPLETED；(2) 取资金账户 glAccountCode；
     * (3) 逐行：校验应付供应商 == 单据供应商（否则 IllegalArgumentException），再
     * {@link SettlementService#settlePayable}（超额由核销引擎抛 OverSettlementException）；
     * (4) {@link AutoVoucherService#generateForPaymentDisbursement}（借 220202、贷 glAccountCode）。
     * 任一失败整单回滚。
     */
    @Transactional
    public PaymentDisbursement post(String docNo, String operator) {
        PaymentDisbursement posted = paymentDisbursementService.post(docNo, operator);
        PaymentAccount account = paymentAccountService.get(posted.getPaymentAccountId());
        // 停用的资金账户不得再用于过账（"停用后新单据不得引用"，过账=资金流动的实际时点）
        if (account.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("资金账户[" + account.getCode() + "] 已停用，不能用于付款过账");
        }
        for (PaymentDisbursementLine line : posted.getLines()) {
            AccountsPayable payable = payableRepository.findById(line.getPayableId())
                    .orElseThrow(() -> new PayableNotFoundException(line.getPayableId()));
            if (payable.getSupplierId() != posted.getSupplierId()) {
                throw new IllegalArgumentException("付款单[" + posted.getDocNo() + "] 供应商="
                        + posted.getSupplierId() + " 与应付[id=" + line.getPayableId()
                        + "] 供应商=" + payable.getSupplierId() + " 不一致，禁止跨供应商核销");
            }
            settlementService.settlePayable(line.getPayableId(), line.getAllocatedAmount(),
                    posted.getPaymentDate(), posted.getDocNo(), operator);
        }
        autoVoucherService.generateForPaymentDisbursement(posted, account.getGlAccountCode(), operator);
        return posted;
    }

    /** 按单据号查（不存在抛 PaymentDisbursementNotFoundException → 404） */
    @Transactional(readOnly = true)
    public PaymentDisbursement get(String docNo) {
        return paymentDisbursementService.get(docNo);
    }

    /** 分页查询（按供应商/资金账户/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<PaymentDisbursement> search(PaymentDisbursementQuery query) {
        return paymentDisbursementService.search(query);
    }
}
