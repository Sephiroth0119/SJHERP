package com.sjherp.app.collection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.collection.CollectionDtos.CollectionReceiptLineRequest;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptLineInput;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.collection.CollectionReceiptService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableService;
import com.sjherp.domain.settlement.SettlementService;

/**
 * 收款单应用服务（M4-T04b）：REST {@code CollectionReceiptController} 与 Agent 工具的公共入口。
 *
 * <p>职责（照 {@code PurchaseInvoiceAppService}）：
 * <ul>
 *   <li>建单：自动 RCPT- 编号 → 调领域 {@link CollectionReceiptService#create}；</li>
 *   <li>审核：直接委托领域服务；</li>
 *   <li><b>过账（验收核心，设计真源 §2.3）</b>：在<b>同一 {@code @Transactional}</b> 内——
 *       (1) 推进单据状态机至 COMPLETED；(2) 取资金账户拿 glAccountCode；
 *       (3) 逐行核销应收（先校验应收客户 == 单据客户，再经核销引擎冲减应收子账）；
 *       (4) 生成现金侧凭证（借 glAccountCode 现金/银行、贷 1122 应收）。
 *       任一失败整单回滚（资金/核销/GL/单据状态不半生效）；</li>
 *   <li>查询：直接委托领域服务。</li>
 * </ul>
 *
 * <p>对手方一致性：每行的应收客户必须等于收款单客户，防跨客户误核销（设计真源 §6.3）。
 * 超额核销由核销引擎（{@code AccountsReceivable.settle}）硬拒（OverSettlementException）→ 整单回滚
 * （不能收超过欠款）。
 */
@Service
public class CollectionReceiptAppService {

    /** 收款单编号规则：RCPT-202606-0001 */
    static final DocumentNumberRule COLLECTION_RECEIPT_RULE = DocumentNumberRule.of("RCPT");

    private final CollectionReceiptService collectionReceiptService;
    private final PaymentAccountService paymentAccountService;
    private final ReceivableService receivableService;
    private final SettlementService settlementService;
    private final AutoVoucherService autoVoucherService;
    private final DocumentNumberGenerator numberGenerator;

    public CollectionReceiptAppService(CollectionReceiptService collectionReceiptService,
                                       PaymentAccountService paymentAccountService,
                                       ReceivableService receivableService,
                                       SettlementService settlementService,
                                       AutoVoucherService autoVoucherService,
                                       DocumentNumberGenerator numberGenerator) {
        this.collectionReceiptService = Objects.requireNonNull(collectionReceiptService,
                "collectionReceiptService 不能为空");
        this.paymentAccountService = Objects.requireNonNull(paymentAccountService,
                "paymentAccountService 不能为空");
        this.receivableService = Objects.requireNonNull(receivableService,
                "receivableService 不能为空");
        this.settlementService = Objects.requireNonNull(settlementService,
                "settlementService 不能为空");
        this.autoVoucherService = Objects.requireNonNull(autoVoucherService,
                "autoVoucherService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    /**
     * 创建收款单（草稿）：分摊本次收款到若干笔应收，自动 RCPT- 编号。
     *
     * @param customerId       客户 id
     * @param paymentAccountId 收入的资金账户 id
     * @param receiptDate      收款日期（为空时默认今天）
     * @param remark           收款说明（可空）
     * @param lines            分摊行（应收 id + 分摊金额）
     * @param operator         操作人
     */
    @Transactional
    public CollectionReceipt create(long customerId, long paymentAccountId, LocalDate receiptDate,
                                    String remark, List<CollectionReceiptLineRequest> lines,
                                    String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("收款单至少要有一行");
        }
        List<CollectionReceiptLineInput> domainLines = new ArrayList<>(lines.size());
        for (CollectionReceiptLineRequest input : lines) {
            if (input.receivableId() == null) {
                throw new IllegalArgumentException("分摊行引用的应收账款 id 不能为空");
            }
            domainLines.add(new CollectionReceiptLineInput(input.receivableId(),
                    input.allocatedAmount()));
        }
        LocalDate effectiveDate = receiptDate != null ? receiptDate : LocalDate.now();
        String docNo = numberGenerator.generate(COLLECTION_RECEIPT_RULE);
        return collectionReceiptService.create(docNo, customerId, paymentAccountId, effectiveDate,
                remark, domainLines, operator);
    }

    /** 审核收款单（DRAFT → APPROVED） */
    @Transactional
    public CollectionReceipt approve(String docNo, String operator) {
        return collectionReceiptService.approve(docNo, operator);
    }

    /**
     * 过账收款单（验收核心）：同一事务内推进状态机 → 逐行核销应收 → 生成现金侧凭证。
     *
     * <p>原子性（设计真源 §2.3）：(1) 单据 →COMPLETED；(2) 取资金账户 glAccountCode；
     * (3) 逐行：校验应收客户 == 单据客户（否则 IllegalArgumentException），再
     * {@link SettlementService#settleReceivable}（超额由核销引擎抛 OverSettlementException）；
     * (4) {@link AutoVoucherService#generateForCollectionReceipt}（借 glAccountCode、贷 1122）。
     * 任一失败整单回滚。
     */
    @Transactional
    public CollectionReceipt post(String docNo, String operator) {
        CollectionReceipt posted = collectionReceiptService.post(docNo, operator);
        PaymentAccount account = paymentAccountService.get(posted.getPaymentAccountId());
        // 停用的资金账户不得再用于过账（"停用后新单据不得引用"，过账=资金流动的实际时点）
        if (account.getStatus() != ArchiveStatus.ENABLED) {
            throw new IllegalArgumentException("资金账户[" + account.getCode() + "] 已停用，不能用于收款过账");
        }
        for (CollectionReceiptLine line : posted.getLines()) {
            AccountsReceivable receivable = receivableService.get(line.getReceivableId());
            if (receivable.getCustomerId() != posted.getCustomerId()) {
                throw new IllegalArgumentException("收款单[" + posted.getDocNo() + "] 客户="
                        + posted.getCustomerId() + " 与应收[id=" + line.getReceivableId()
                        + "] 客户=" + receivable.getCustomerId() + " 不一致，禁止跨客户核销");
            }
            settlementService.settleReceivable(line.getReceivableId(), line.getAllocatedAmount(),
                    posted.getReceiptDate(), posted.getDocNo(), operator);
        }
        autoVoucherService.generateForCollectionReceipt(posted, account.getGlAccountCode(), operator);
        return posted;
    }

    /** 按单据号查（不存在抛 CollectionReceiptNotFoundException → 404） */
    @Transactional(readOnly = true)
    public CollectionReceipt get(String docNo) {
        return collectionReceiptService.get(docNo);
    }

    /** 分页查询（按客户/资金账户/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<CollectionReceipt> search(CollectionReceiptQuery query) {
        return collectionReceiptService.search(query);
    }
}
