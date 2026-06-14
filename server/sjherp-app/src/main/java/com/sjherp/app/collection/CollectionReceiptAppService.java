package com.sjherp.app.collection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.collection.CollectionDtos.CollectionReceiptLineRequest;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptLineInput;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.collection.CollectionReceiptService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableService;
import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementRecordRepository;
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
    private final SettlementRecordRepository settlementRecordRepository;
    private final AutoVoucherService autoVoucherService;
    private final VoucherService voucherService;
    private final VoucherAppService voucherAppService;
    private final DocumentNumberGenerator numberGenerator;

    public CollectionReceiptAppService(CollectionReceiptService collectionReceiptService,
                                       PaymentAccountService paymentAccountService,
                                       ReceivableService receivableService,
                                       SettlementService settlementService,
                                       SettlementRecordRepository settlementRecordRepository,
                                       AutoVoucherService autoVoucherService,
                                       VoucherService voucherService,
                                       VoucherAppService voucherAppService,
                                       DocumentNumberGenerator numberGenerator) {
        this.collectionReceiptService = Objects.requireNonNull(collectionReceiptService,
                "collectionReceiptService 不能为空");
        this.paymentAccountService = Objects.requireNonNull(paymentAccountService,
                "paymentAccountService 不能为空");
        this.receivableService = Objects.requireNonNull(receivableService,
                "receivableService 不能为空");
        this.settlementService = Objects.requireNonNull(settlementService,
                "settlementService 不能为空");
        this.settlementRecordRepository = Objects.requireNonNull(settlementRecordRepository,
                "settlementRecordRepository 不能为空");
        this.autoVoucherService = Objects.requireNonNull(autoVoucherService,
                "autoVoucherService 不能为空");
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.voucherAppService = Objects.requireNonNull(voucherAppService,
                "voucherAppService 不能为空");
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

    /**
     * 冲销收款单（红字单，M4-T07c，最高风险路径，不可逆）：同一外层 {@code @Transactional} 编排——
     * <ol>
     *   <li>校验原单 COMPLETED + 未冲销（领域层 reverse 仍兜底再校验，幂等：已 REVERSED 拒）；</li>
     *   <li>反向核销应收：按 {@code paymentDocNo == 单号} 反查<b>正向</b>核销记录（type=RECEIVABLE、amount&gt;0），
     *       逐条 {@link SettlementService#unsettleReceivable}（子账 settled 回退、状态回 PARTIAL/OPEN，并追加负额
     *       反向核销记录）。只取正向记录避免对已有反向记录二次反向；</li>
     *   <li>红冲现金侧凭证：{@code findBySourceDocNo(单号)} 取 COLLECTION_RECEIPT 自动凭证（借现金/贷应收）→
     *       {@link VoucherAppService#reverse} 生成借贷对调红字凭证并在原账期过账（账期已关账 →
     *       {@code PeriodClosedException} → 整 reverse 回滚，闭月天然受保护，设计真源 §73）；</li>
     *   <li>红字凭证号作为 {@code reversalDocNo} → {@link CollectionReceiptService#reverse}：原单 COMPLETED → REVERSED。</li>
     * </ol>
     * 任一步失败整事务回滚（子账 settled/负额核销记录/现金侧凭证/单据状态一致）。这解锁了 T07b 暂拒的
     * "已核销发票红冲"（先冲收款单→应收 settled 回 0→canBeReversed=true→再红冲销售发票）。无对应自动凭证
     * （理论上金额&gt;0 必有，防御）时用合成冲销引用。
     *
     * @param docNo    被冲销的收款单号（须 COMPLETED）
     * @param operator 操作人
     * @return 已转 REVERSED 的原收款单
     */
    @Transactional
    public CollectionReceipt reverse(String docNo, String operator) {
        // 前置状态守门（主防线，评审 P2）：已冲销/非已过账即拒，绝不依赖后续 unsettle 下溢兜底——
        // 否则对已 REVERSED 单二次冲销会先跑反向核销、靠 AccountsReceivable.unsettle 下溢异常间接拒绝，
        // 报错误导（提示超额而非"已冲销"）。领域层 reverse 仍兜底再校验。
        CollectionReceipt receipt = collectionReceiptService.get(docNo);
        if (receipt.getStatus() == DocumentStatus.REVERSED) {
            throw new IllegalStateException("收款单[" + docNo + "] 已冲销，不可重复冲销");
        }
        if (receipt.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalStateException("仅已过账（COMPLETED）收款单可冲销，当前状态=" + receipt.getStatus());
        }
        // 反向核销：只对正向核销记录（amount>0）逐条 unsettle，回退应收子账已核销额
        for (SettlementRecord record : settlementRecordRepository.findByPaymentDocNo(docNo)) {
            if (record.getAmount().signum() > 0) {
                settlementService.unsettleReceivable(record.getTargetId(), record.getAmount(),
                        receipt.getReceiptDate(), docNo, operator);
            }
        }
        String reversalDocNo = reverseAutoVoucher(docNo, operator);
        return collectionReceiptService.reverse(receipt.getDocNo(), reversalDocNo, operator);
    }

    /**
     * 红冲收款单现金侧自动凭证：按来源单据号取 COLLECTION_RECEIPT 类型的自动凭证 → 冲销 → 返回红字凭证号。
     * 已过账收款单必生成现金侧凭证（金额&gt;0），故缺失即账证不符（异常数据）——抛 IllegalStateException 整事务
     * 回滚，绝不以合成引用静默把单标 REVERSED 而无红字凭证（评审 P3，账证一致红线）。
     */
    private String reverseAutoVoucher(String docNo, String operator) {
        return voucherService.findBySourceDocNo(docNo).stream()
                .filter(v -> VoucherSourceType.COLLECTION_RECEIPT.name().equals(v.getSourceDocType()))
                .findFirst()
                .map(Voucher::getDocNo)
                .map(voucherDocNo -> voucherAppService.reverse(voucherDocNo, operator).getDocNo())
                .orElseThrow(() -> new IllegalStateException("收款单[" + docNo
                        + "] 无对应现金侧自动凭证，无法红冲（账证不符，需排查）"));
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
