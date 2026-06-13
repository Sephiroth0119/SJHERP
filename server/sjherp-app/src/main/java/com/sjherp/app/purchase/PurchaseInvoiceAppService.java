package com.sjherp.app.purchase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.purchase.PurchaseDtos.PurchaseInvoiceLineRequest;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
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

    public PurchaseInvoiceAppService(PurchaseInvoiceService purchaseInvoiceService,
                                     PurchaseReceiptService purchaseReceiptService,
                                     PurchaseOrderService purchaseOrderService,
                                     SupplierService supplierService,
                                     AccountsPayableRepository accountsPayableRepository,
                                     DocumentNumberGenerator numberGenerator) {
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

    /** 过账采购发票（APPROVED → EXECUTING → COMPLETED，生成应付账款） */
    @Transactional
    public PurchaseInvoice post(String docNo, String operator) {
        PurchaseInvoice invoice = purchaseInvoiceService.get(docNo);
        // 到期日按供应商当前结算方式推算（取自供应商档案，与建单时一致）
        Supplier supplier = supplierService.get(invoice.getSupplierId());
        return purchaseInvoiceService.post(docNo, supplier.getSettlementMethod(), operator);
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
