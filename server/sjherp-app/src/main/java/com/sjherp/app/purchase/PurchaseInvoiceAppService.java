package com.sjherp.app.purchase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseInvoiceLineRequest;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableQuery;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceLineInput;
import com.sjherp.domain.purchase.PurchaseInvoiceQuery;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptService;

/**
 * 采购发票应用服务（M3-T07）：REST {@code PurchaseInvoiceController} 与应付查询的公共入口。
 *
 * <p>职责：
 * <ul>
 *   <li>建单：从来源链解析供应商（收货单 → 采购订单 → 供应商）并取其结算方式，自动 PINV- 编号 →
 *       调领域 {@link PurchaseInvoiceService#create}（三单匹配「开票数量 ≤ 已收数量」在领域层）；</li>
 *   <li>审核 / 过账 / 查询：直接委托领域服务；过账时再次取供应商结算方式传入用于到期日推算；</li>
 *   <li><b>外层事务</b>：写方法标 {@code @Transactional}，把单据状态变更 + 应付生成包成原子事务；</li>
 *   <li>应付列表查询（{@link #searchPayables}）：登录即可（无权限点）。</li>
 * </ul>
 */
@Service
public class PurchaseInvoiceAppService {

    /** 采购发票编号规则：PINV-202606-0001 */
    static final DocumentNumberRule PURCHASE_INVOICE_RULE = DocumentNumberRule.of("PINV");

    private final PurchaseInvoiceService purchaseInvoiceService;
    private final PurchaseReceiptService purchaseReceiptService;
    private final PurchaseOrderService purchaseOrderService;
    private final SupplierService supplierService;
    private final AccountsPayableRepository accountsPayableRepository;
    private final DocumentNumberGenerator numberGenerator;
    private final AutoVoucherService autoVoucherService;
    private final VoucherService voucherService;
    private final VoucherAppService voucherAppService;

    public PurchaseInvoiceAppService(PurchaseInvoiceService purchaseInvoiceService,
                                     PurchaseReceiptService purchaseReceiptService,
                                     PurchaseOrderService purchaseOrderService,
                                     SupplierService supplierService,
                                     AccountsPayableRepository accountsPayableRepository,
                                     DocumentNumberGenerator numberGenerator,
                                     AutoVoucherService autoVoucherService,
                                     VoucherService voucherService,
                                     VoucherAppService voucherAppService) {
        this.purchaseInvoiceService = Objects.requireNonNull(purchaseInvoiceService,
                "purchaseInvoiceService 不能为空");
        this.purchaseReceiptService = Objects.requireNonNull(purchaseReceiptService,
                "purchaseReceiptService 不能为空");
        this.purchaseOrderService = Objects.requireNonNull(purchaseOrderService,
                "purchaseOrderService 不能为空");
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService 不能为空");
        this.accountsPayableRepository = Objects.requireNonNull(accountsPayableRepository,
                "accountsPayableRepository 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.autoVoucherService = Objects.requireNonNull(autoVoucherService,
                "autoVoucherService 不能为空");
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.voucherAppService = Objects.requireNonNull(voucherAppService,
                "voucherAppService 不能为空");
    }

    /**
     * 创建采购发票（草稿）：引用某采购入库单开票，自动 PINV- 编号。
     *
     * @param purchaseReceiptNo 引用的采购入库单号（必须 COMPLETED）
     * @param invoiceDate       发票日期（为空时默认今天）
     * @param supplierInvoiceNo 供应商发票号（可空）
     * @param remark            发票说明（可空）
     * @param lines             行输入（引用收货行 + 开票数量 + 开票金额）
     * @param operator          操作人
     */
    @Transactional
    public PurchaseInvoice create(String purchaseReceiptNo, LocalDate invoiceDate,
                                  String supplierInvoiceNo, String remark,
                                  List<PurchaseInvoiceLineRequest> lines, String operator) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("采购发票至少要有一行");
        }
        Supplier supplier = resolveSupplier(purchaseReceiptNo);
        List<PurchaseInvoiceLineInput> domainLines = new ArrayList<>(lines.size());
        for (PurchaseInvoiceLineRequest input : lines) {
            if (input.receiptLineNo() == null) {
                throw new IllegalArgumentException("发票行引用的采购入库单行号不能为空");
            }
            domainLines.add(new PurchaseInvoiceLineInput(input.receiptLineNo(), input.quantity(),
                    input.amount()));
        }
        LocalDate effectiveDate = invoiceDate != null ? invoiceDate : LocalDate.now();
        String docNo = numberGenerator.generate(PURCHASE_INVOICE_RULE);
        return purchaseInvoiceService.create(docNo, purchaseReceiptNo, supplier.getId(),
                supplier.getSettlementMethod(), effectiveDate, supplierInvoiceNo, remark,
                domainLines, operator);
    }

    /** 审核采购发票（DRAFT → APPROVED） */
    @Transactional
    public PurchaseInvoice approve(String docNo, String operator) {
        return purchaseInvoiceService.approve(docNo, operator);
    }

    /**
     * 过账采购发票（APPROVED → EXECUTING → COMPLETED，生成应付账款）；
     * 同事务内自动生成记账凭证（借 220201 暂估应付款 / 贷 220202 应付账款，T02）。
     */
    @Transactional
    public PurchaseInvoice post(String docNo, String operator) {
        PurchaseInvoice invoice = purchaseInvoiceService.get(docNo);
        // 到期日按供应商当前结算方式推算（取自供应商档案，与建单时一致）
        Supplier supplier = supplierService.get(invoice.getSupplierId());
        PurchaseInvoice posted = purchaseInvoiceService.post(docNo, supplier.getSettlementMethod(),
                operator);
        autoVoucherService.generateForPurchaseInvoice(posted, operator);   // T02 自动凭证
        return posted;
    }

    /**
     * 冲销采购发票（红字发票，M4-T07b，不可逆）：同一外层 {@code @Transactional} 编排——
     * <ol>
     *   <li>取原应付（{@code findBySourceDocNo(发票号)}）校验 {@code canBeReversed}（无核销且仍 OPEN）——
     *       已（部分）核销的应付须先冲对应付款单（T07c），否则前置拒绝清晰报错（设计真源 §1.7）；</li>
     *   <li>红冲采购发票自动凭证（借 220201 暂估应付 / 贷 220202 应付账款）→ {@link VoucherAppService#reverse}
     *       生成借贷对调红字凭证并在原账期过账（账期已关账 → {@code PeriodClosedException} → 整 reverse 回滚）；</li>
     *   <li>{@link PurchaseInvoiceService#reverse}（回退收货行已开票量 + 原发票 COMPLETED → REVERSED，
     *       reversalDocNo=红字凭证号）；</li>
     *   <li>应付 {@code markReversed} → 仓储 save 落 REVERSED 状态。</li>
     * </ol>
     * 任一步失败整事务回滚。幂等：原单已 REVERSED → 领域层拒；自动凭证红冲幂等兜底。
     *
     * @param docNo    被冲销的采购发票号（须 COMPLETED）
     * @param operator 操作人
     * @return 已转 REVERSED 的原采购发票
     */
    @Transactional
    public PurchaseInvoice reverse(String docNo, String operator) {
        // 先取原单与应付，前置校验可冲销（无核销），避免对脏单白白红冲凭证（领域层 reverse 仍兜底再校验）
        purchaseInvoiceService.get(docNo);   // 不存在抛 PurchaseInvoiceNotFoundException → 404
        AccountsPayable payable = requireReversiblePayable(docNo);
        String reversalDocNo = reverseAutoVoucher(docNo, operator);
        PurchaseInvoice reversed = purchaseInvoiceService.reverse(docNo, reversalDocNo, operator);
        // 应付冲回（OPEN → REVERSED，账龄与一致性勾稽据此排除该笔）
        payable.markReversed(operator);
        accountsPayableRepository.save(payable);
        return reversed;
    }

    /**
     * 取原应付并校验可冲销：无对应应付（理论上发票过账必生成，防御）或已（部分）核销 → 拒绝。
     */
    private AccountsPayable requireReversiblePayable(String invoiceDocNo) {
        AccountsPayable payable = accountsPayableRepository.findBySourceDocNo(invoiceDocNo).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "采购发票[" + invoiceDocNo + "] 未找到对应应付台账，无法冲销"));
        if (!payable.canBeReversed()) {
            throw new IllegalStateException("采购发票[" + invoiceDocNo + "] 对应应付已核销或非未核销状态，"
                    + "不可直接冲销发票——请先冲销对应的付款单后再冲发票");
        }
        return payable;
    }

    /**
     * 红冲采购发票自动凭证：按来源单据号取 PURCHASE_INVOICE 类型的自动凭证 → 冲销 → 返回红字凭证号。
     * 无对应自动凭证（金额>0 时不应发生，防御）→ 返回合成冲销引用 {@code REVERSAL:<发票号>}。
     */
    private String reverseAutoVoucher(String docNo, String operator) {
        return voucherService.findBySourceDocNo(docNo).stream()
                .filter(v -> VoucherSourceType.PURCHASE_INVOICE.name().equals(v.getSourceDocType()))
                .findFirst()
                .map(Voucher::getDocNo)
                .map(voucherDocNo -> voucherAppService.reverse(voucherDocNo, operator).getDocNo())
                .orElse("REVERSAL:" + docNo);
    }

    /** 按单据号查（不存在抛 PurchaseInvoiceNotFoundException → 404） */
    @Transactional(readOnly = true)
    public PurchaseInvoice get(String docNo) {
        return purchaseInvoiceService.get(docNo);
    }

    /** 分页查询发票（按供应商/采购入库单号/状态过滤，可空） */
    @Transactional(readOnly = true)
    public PageResult<PurchaseInvoice> search(PurchaseInvoiceQuery query) {
        return purchaseInvoiceService.search(query);
    }

    /** 分页查询应付账款（GET /api/payables，登录即可，无权限点） */
    @Transactional(readOnly = true)
    public PageResult<AccountsPayable> searchPayables(AccountsPayableQuery query) {
        return accountsPayableRepository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ---------------------------------------------------------------
    // 来源链解析：收货单 → 采购订单 → 供应商（取供应商 id 与结算方式）
    // ---------------------------------------------------------------

    private Supplier resolveSupplier(String purchaseReceiptNo) {
        PurchaseReceipt receipt = purchaseReceiptService.get(purchaseReceiptNo);
        PurchaseOrder order = purchaseOrderService.get(receipt.getPurchaseOrderNo());
        return supplierService.get(order.getSupplierId());
    }
}
