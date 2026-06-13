package com.sjherp.domain.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.payable.AccountsPayable;

/**
 * 采购发票领域服务（M3-T07，路线图 §5 采购线，为 M4 应付铺路）。
 *
 * <p>所有采购发票写操作的唯一入口（CLAUDE.md 原则 1）。纯 Java 零依赖：依赖采购发票仓储端口
 * {@link PurchaseInvoiceRepository}、采购入库领域服务 {@link PurchaseReceiptService}（读引用收货单
 * 做三单匹配）与应付端口 {@link AccountsPayablePort}（过账生成应付），由 app 层装配并把单据状态
 * 变更 + 应付生成包进同一外层事务（拆解 §1.4）。
 *
 * <h2>建单：引用采购入库单 + 三单匹配从简（CLAUDE.md 原则 2 财务专业性）</h2>
 * 引用的采购入库单必须已过账（COMPLETED，库存已入账才可开票）；各行引用有效的收货行，商品与
 * 收货行一致，<b>开票数量 ≤ 剩余可开票量（= 收货量 − 已开票量）</b>——<b>跨发票累计</b>校验
 * （收货行 invoicedQty 记录历史累计开票量，本发票内多行引用同一收货行再叠加）。校验超额拒绝——
 * 发票数量不超过实际收到的货，防止跨发票超额开票虚增应付（CLAUDE.md 原则 2；原则 1：宁可拒绝不可破坏模型）。
 * 发票金额由开票方给出（容许运费/折扣/税差与「数量×收货单价」不同），不强制等于数量乘单价。
 *
 * <h2>过账口径：生成应付账款 + 回写已开票量</h2>
 * 审核（APPROVED）后过账：单据 EXECUTING → COMPLETED，<b>同事务回写收货行累计已开票量</b>
 * （{@link PurchaseReceiptService#recordInvoiced}，守门累计开票 ≤ 收货量），并生成一笔应付账款
 * （{@code domain/payable}）：供应商 = 来源链供应商、金额 = 发票总额、来源单据号 = 发票号、
 * 到期日由供应商结算方式推算、状态 OPEN（未核销）。本期不做核销（核销 M4-T03，应付留 settledAmount 字段与 TODO）。
 * 过账幂等：同发票号已生成应付则不重复生成（{@link AccountsPayablePort#findBySourceDocNo}）。
 */
public class PurchaseInvoiceService {

    private final PurchaseInvoiceRepository repository;
    private final PurchaseReceiptService purchaseReceiptService;
    private final AccountsPayablePort accountsPayable;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计 */
    private final DomainEventPublisher eventPublisher;

    public PurchaseInvoiceService(PurchaseInvoiceRepository repository,
                                  PurchaseReceiptService purchaseReceiptService,
                                  AccountsPayablePort accountsPayable,
                                  DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.purchaseReceiptService = Objects.requireNonNull(purchaseReceiptService,
                "purchaseReceiptService 不能为空");
        this.accountsPayable = Objects.requireNonNull(accountsPayable, "accountsPayable 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建采购发票（草稿）：引用某采购入库单开票，三单匹配校验开票数量不超过已收。
     *
     * @param docNo             单据号（PINV-年月-序号，app 层用 DocumentNumberGenerator 生成）
     * @param purchaseReceiptNo 引用的采购入库单号（必须 COMPLETED）
     * @param supplierId        供应商 id（app 层取自来源链：收货单→采购订单→供应商）
     * @param settlementMethod  供应商结算方式（app 层取自供应商档案，用于到期日推算）
     * @param invoiceDate       发票日期（到期日推算基准）
     * @param supplierInvoiceNo 供应商发票号（可空）
     * @param remark            发票说明（可空）
     * @param lines             行输入（引用收货行 + 开票数量 + 开票金额）
     * @param operator          操作人
     */
    @Audited(action = "purchase_invoice.create", targetType = "purchase_invoice")
    public PurchaseInvoice create(String docNo, String purchaseReceiptNo, long supplierId,
                                  SettlementMethod settlementMethod, LocalDate invoiceDate,
                                  String supplierInvoiceNo, String remark,
                                  List<PurchaseInvoiceLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(purchaseReceiptNo, "引用的采购入库单号不能为空");
        Objects.requireNonNull(settlementMethod, "结算方式不能为空");
        Objects.requireNonNull(lines, "采购发票行不能为空");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("采购发票至少要有一行");
        }

        PurchaseReceipt receipt = purchaseReceiptService.get(purchaseReceiptNo);
        if (receipt.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalArgumentException("采购入库单[" + purchaseReceiptNo + "] 当前状态 "
                    + receipt.getStatus() + " 未过账，不可开票（库存入账后才可开票）");
        }
        Map<Integer, PurchaseReceiptLine> receiptLineByNo = new HashMap<>();
        for (PurchaseReceiptLine rl : receipt.getLines()) {
            receiptLineByNo.put(rl.getLineNo(), rl);
        }

        // 跨发票累计校验「开票数量 ≤ 收货量 − 已开票量（剩余可开票量）」（三单匹配从简）：
        // 起点取收货行已开票量 invoicedQty（跨发票累计），本发票内多行引用同一收货行再叠加，
        // 守住「应付不超实收」红线（CLAUDE.md 原则 2，防跨发票超额开票虚增应付）。
        Map<Integer, BigDecimal> invoicedByReceiptLine = new HashMap<>();
        List<PurchaseInvoiceLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (PurchaseInvoiceLineInput input : lines) {
            PurchaseReceiptLine receiptLine = receiptLineByNo.get(input.receiptLineNo());
            if (receiptLine == null) {
                throw new IllegalArgumentException("采购入库单[" + purchaseReceiptNo + "] 不存在行号 "
                        + input.receiptLineNo());
            }
            BigDecimal qty = input.quantity();
            if (qty == null || qty.signum() <= 0) {
                throw new IllegalArgumentException("开票数量必须大于 0: 引用收货行 " + input.receiptLineNo());
            }
            // 本发票内累计起点 = 收货行已开票量（跨发票），后续同收货行多行再叠加
            BigDecimal alreadyInvoiced = invoicedByReceiptLine.getOrDefault(
                    input.receiptLineNo(), receiptLine.getInvoicedQty());
            BigDecimal remaining = receiptLine.getQuantity().subtract(alreadyInvoiced);
            if (qty.compareTo(remaining) > 0) {
                throw new IllegalArgumentException("开票数量 " + qty.toPlainString()
                        + " 超过采购入库单行 " + input.receiptLineNo() + " 剩余可开票量 "
                        + remaining.toPlainString() + "（三单匹配：发票不得超过实际收货，含跨发票累计）");
            }
            invoicedByReceiptLine.put(input.receiptLineNo(), alreadyInvoiced.add(qty));
            domainLines.add(PurchaseInvoiceLine.create(lineNo++, input.receiptLineNo(),
                    receiptLine.getProductId(), qty, input.amount()));
        }

        // 结算方式不随发票落库（到期日在过账时由结算方式现算固化到应付），建单仅校验非空（见上）
        PurchaseInvoice invoice = PurchaseInvoice.create(docNo, purchaseReceiptNo, supplierId,
                invoiceDate, supplierInvoiceNo, remark, domainLines, operator);
        invoice.registerEventPublisher(eventPublisher);
        repository.save(invoice);
        return invoice;
    }

    /** 审核采购发票：DRAFT → APPROVED（业务内容自此锁定）。 */
    @Audited(action = "purchase_invoice.approve", targetType = "purchase_invoice")
    public PurchaseInvoice approve(String docNo, String operator) {
        requireOperator(operator);
        PurchaseInvoice invoice = get(docNo);
        invoice.registerEventPublisher(eventPublisher);
        invoice.approve(operator);
        repository.save(invoice);
        return invoice;
    }

    /**
     * 过账采购发票：APPROVED → EXECUTING → COMPLETED，生成一笔未核销应付账款。
     *
     * <p>到期日由供应商结算方式推算；过账幂等（同发票号已生成应付则不重复）。调用方（app 装配的
     * 本服务）须以外层事务包住「状态流转 + 应付生成」。
     *
     * @param settlementMethod 供应商结算方式（app 层取自供应商档案，用于到期日推算）
     */
    @Audited(action = "purchase_invoice.post", targetType = "purchase_invoice")
    public PurchaseInvoice post(String docNo, SettlementMethod settlementMethod, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(settlementMethod, "结算方式不能为空");
        PurchaseInvoice invoice = get(docNo);
        invoice.registerEventPublisher(eventPublisher);
        invoice.startExecution(operator);
        // 同事务回写收货行累计已开票量（守门累计开票 ≤ 收货量，防跨发票超额虚增应付）；
        // 状态机保证每张发票只过账一次，故回写恰好一次。
        purchaseReceiptService.recordInvoiced(invoice.getPurchaseReceiptNo(),
                buildInvoicedLines(invoice));
        // 幂等：同发票号已生成应付则不重复生成（防过账重试重账）
        if (accountsPayable.findBySourceDocNo(docNo).isEmpty()) {
            LocalDate dueDate = dueDate(settlementMethod, invoice.getInvoiceDate());
            AccountsPayable payable = AccountsPayable.open(invoice.getSupplierId(),
                    invoice.totalAmount(), docNo, dueDate, operator);
            accountsPayable.save(payable);
        }
        invoice.complete(operator);
        repository.save(invoice);
        return invoice;
    }

    /**
     * 冲销已完成采购发票（红字发票）。
     *
     * <p>TODO（M4-T07 统一做）：生成反向发票驱动红字应付（负向应付抵消原应付），原单
     * COMPLETED → REVERSED 并红字关联。当前未实现——采购发票本身不提供物理删除
     * （CLAUDE.md 原则 2：财务记录只可冲销不可删除）。
     */
    @Audited(action = "purchase_invoice.reverse", targetType = "purchase_invoice")
    public PurchaseInvoice reverse(String docNo, String operator) {
        requireOperator(operator);
        throw new UnsupportedOperationException(
                "采购发票冲销（红字发票）尚未实现，统一在 M4-T07 落地");
    }

    /** 按单据号查（不存在抛 {@link PurchaseInvoiceNotFoundException} → API 404） */
    public PurchaseInvoice get(String docNo) {
        return repository.findByDocNo(docNo)
                .orElseThrow(() -> new PurchaseInvoiceNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<PurchaseInvoice> search(PurchaseInvoiceQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ---------------------------------------------------------------
    // 到期日推算（由供应商结算方式，CLAUDE.md 原则 2 会计逻辑一致性）
    // ---------------------------------------------------------------

    /**
     * 到期日推算（从简，小企业够用）：
     * <ul>
     *   <li>现结（CASH）/ 预付（PREPAID）：到期日 = 发票日（货到付款 / 已先付）；</li>
     *   <li>月结（MONTHLY）：到期日 = 次月同日（按月对账后结算，简化为 +1 个月）。</li>
     * </ul>
     */
    /** 开票回写行：发票各行的引用收货行号 → 开票数量（供过账同事务回写收货行累计已开票量） */
    private static List<PurchaseReceiptService.InvoicedLine> buildInvoicedLines(PurchaseInvoice invoice) {
        List<PurchaseReceiptService.InvoicedLine> invoiced = new ArrayList<>(invoice.getLines().size());
        for (PurchaseInvoiceLine line : invoice.getLines()) {
            invoiced.add(new PurchaseReceiptService.InvoicedLine(line.getReceiptLineNo(), line.getQuantity()));
        }
        return invoiced;
    }

    private static LocalDate dueDate(SettlementMethod settlementMethod, LocalDate invoiceDate) {
        return switch (settlementMethod) {
            case CASH, PREPAID -> invoiceDate;
            case MONTHLY -> invoiceDate.plusMonths(1);
        };
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
