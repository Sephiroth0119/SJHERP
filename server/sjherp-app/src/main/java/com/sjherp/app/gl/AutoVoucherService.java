package com.sjherp.app.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLine;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.sales.SalesInvoice;

/**
 * 业务→凭证自动化（M4-T02，路线图 §6，全系统最高风险的财务核心）：M3 单据过账后自动生成对应记账凭证。
 *
 * <p>四个采购/销售过账事件 → 自动凭证（拆解 §1.2 分录表）：
 * <table>
 *   <caption>四事件分录</caption>
 *   <tr><th>源单据 post</th><th>借</th><th>贷</th><th>金额来源</th><th>voucherDate</th></tr>
 *   <tr><td>采购入库</td><td>1405 库存商品</td><td>220201 暂估应付款</td>
 *       <td>{@link PurchaseReceipt#totalAmount()}</td><td>receiptDate</td></tr>
 *   <tr><td>采购发票</td><td>220201 暂估应付款</td><td>220202 应付账款</td>
 *       <td>{@link PurchaseInvoice#totalAmount()}</td><td>invoiceDate</td></tr>
 *   <tr><td>销售出库</td><td>6401 主营业务成本</td><td>1405 库存商品</td>
 *       <td>{@link SalesDelivery#totalCogs()}</td><td>过账日(UTC，出库单无业务日)</td></tr>
 *   <tr><td>销售发票</td><td>1122 应收账款</td><td>6001 主营业务收入</td>
 *       <td>{@link SalesInvoice#totalAmount()}</td><td>invoiceDate</td></tr>
 * </table>
 *
 * <p>由各 {@code *AppService.post} 在业务过账之后、同一 {@code @Transactional} 内直调
 * （拆解 §4 否决事件方案——事件吞异常破坏原子性 + 财务凭证不可最终一致）。
 *
 * <h2>每方法统一流程（拆解 §2）</h2>
 * <ol>
 *   <li>取金额，{@code signum() <= 0} 直接 return（COGS=0/金额≤0 无金额无凭证，否则 Voucher.create 抛不平衡）；</li>
 *   <li>幂等查重：{@code findBySourceDocNo} 非空即 return（每单一组、重过账/重试不重复，验收核心）；</li>
 *   <li>由 voucherDate 推算账期 + 生成 VCH- 号（按凭证日期所属年月段计序，与 VoucherAppService 一致）；</li>
 *   <li>确保账期存在（不存在自动 open，撞键容错；CLOSED 留给 post 守卫抛 PeriodClosedException 回滚）；</li>
 *   <li>组装借贷两行 {@link VoucherLineInput} → {@link VoucherService#createFromSource} 建草稿 → {@link VoucherService#post} 过账。</li>
 * </ol>
 *
 * <p><b>无新增权限点</b>：自动凭证由业务过账权限驱动，非独立用户动作（拆解 §4）。
 */
public class AutoVoucherService {

    // ---------------- 科目编码常量（拆解 §1.2，对应 V19/V20 预置科目） ----------------

    /** 220201 应付账款—暂估应付款（负债/贷，货到票未到暂估，V20 新增） */
    private static final String ACC_PAYABLE_ESTIMATED = "220201";
    /** 220202 应付账款—应付账款（负债/贷，正式应付，与 accounts_payable 子账勾稽，V20 新增） */
    private static final String ACC_PAYABLE_FORMAL = "220202";
    /** 6401 主营业务成本（损益/借） */
    private static final String ACC_COGS = "6401";
    /** 1122 应收账款（资产/借） */
    private static final String ACC_RECEIVABLE = "1122";
    /** 6001 主营业务收入（损益/贷） */
    private static final String ACC_REVENUE = "6001";

    /**
     * 1001 库存现金（资产/借，M4-T04 默认/文档常量）。
     *
     * <p>收/付款单现金侧实际借/贷科目由 {@code paymentAccount.glAccountCode} 动态传入
     * （{@link #generateForCollectionReceipt}/{@link #generateForPaymentDisbursement} 的入参），
     * 本常量与 {@link #ACC_BANK} 仅作默认值/文档说明，<b>不写死</b>分录科目。
     */
    @SuppressWarnings("unused")
    private static final String ACC_CASH = "1001";
    /** 1002 银行存款（资产/借，M4-T04 默认/文档常量，实际科目动态传入；见 {@link #ACC_CASH} 说明） */
    @SuppressWarnings("unused")
    private static final String ACC_BANK = "1002";

    /** 凭证编号规则：VCH-202606-0001（与 {@link VoucherAppService#VOUCHER_RULE} 一致） */
    private static final DocumentNumberRule VOUCHER_RULE = DocumentNumberRule.of("VCH");

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final VoucherService voucherService;
    private final AccountingPeriodService accountingPeriodService;
    private final DocumentNumberGenerator numberGenerator;
    private final ProductRepository productRepository;
    private final InventoryAccountPolicy inventoryAccountPolicy;

    public AutoVoucherService(VoucherService voucherService,
                              AccountingPeriodService accountingPeriodService,
                              DocumentNumberGenerator numberGenerator,
                              ProductRepository productRepository,
                              InventoryAccountPolicy inventoryAccountPolicy) {
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.accountingPeriodService = Objects.requireNonNull(accountingPeriodService,
                "accountingPeriodService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository 不能为空");
        this.inventoryAccountPolicy = Objects.requireNonNull(inventoryAccountPolicy,
                "inventoryAccountPolicy 不能为空");
    }

    /**
     * 采购入库过账 → 凭证：借 1405 库存商品 / 贷 220201 暂估应付款，金额=入库成本，voucherDate=收货日期。
     *
     * @param receipt  已过账（COMPLETED）的采购入库单
     * @param operator 操作人（沿用业务过账操作人）
     */
    public void generateForPurchaseReceipt(PurchaseReceipt receipt, String operator) {
        Objects.requireNonNull(receipt, "采购入库单不能为空");
        BigDecimal amount = receipt.totalAmount();
        String summary = VoucherSourceType.PURCHASE_RECEIPT.label() + " " + receipt.getDocNo();
        generate(VoucherSourceType.PURCHASE_RECEIPT, receipt.getDocNo(), amount,
                receipt.getReceiptDate(), summary,
                () -> purchaseReceiptLines(receipt, amount, summary), operator);
    }

    /**
     * 采购发票过账 → 凭证：借 220201 暂估应付款 / 贷 220202 应付账款，金额=发票额，voucherDate=发票日期。
     * 发票按发票额冲回暂估（220201 余额=已收货未开票部分；部分开票留余额，全开票回零，拆解 §1.2）。
     *
     * @param invoice  已过账（COMPLETED）的采购发票
     * @param operator 操作人
     */
    public void generateForPurchaseInvoice(PurchaseInvoice invoice, String operator) {
        Objects.requireNonNull(invoice, "采购发票不能为空");
        BigDecimal amount = invoice.totalAmount();
        String summary = VoucherSourceType.PURCHASE_INVOICE.label() + " " + invoice.getDocNo();
        generate(VoucherSourceType.PURCHASE_INVOICE, invoice.getDocNo(), amount,
                invoice.getInvoiceDate(), summary,
                () -> List.of(
                        debitLine(ACC_PAYABLE_ESTIMATED, amount, summary),
                        creditLine(ACC_PAYABLE_FORMAL, amount, summary)), operator);
    }

    /**
     * 销售出库过账 → 凭证：借 6401 主营业务成本 / 贷 1405 库存商品，金额=Σ COGS，
     * voucherDate=createdAt 转 UTC 墙钟日（出库单无业务日，与 SALES_OUT 流水同期，拆解 §1.2/§5）。
     * COGS=0 时跳过生成（无金额无凭证）。
     *
     * @param delivery 已过账（COMPLETED，COGS 已回填）的销售出库单
     * @param operator 操作人
     */
    public void generateForSalesDelivery(SalesDelivery delivery, String operator) {
        Objects.requireNonNull(delivery, "销售出库单不能为空");
        BigDecimal amount = delivery.totalCogs();
        // 出库单无业务日字段：凭证日=过账日（取过账时 UTC 日期）。自动凭证在出库 post 同事务内生成，
        // 故 now=过账时点，与同刻产生的 SALES_OUT 流水落同一账期；不依赖 createdAt（restore 不恢复原值、
        // 语义脆弱，见对抗校验）。TODO：给 SalesDelivery 补 deliveryDate 业务日后改用之。
        LocalDate voucherDate = LocalDate.now(ZoneOffset.UTC);
        String summary = VoucherSourceType.SALES_DELIVERY.label() + " " + delivery.getDocNo();
        generate(VoucherSourceType.SALES_DELIVERY, delivery.getDocNo(), amount,
                voucherDate, summary,
                () -> salesDeliveryLines(delivery, amount, summary), operator);
    }

    /**
     * 销售发票过账 → 凭证：借 1122 应收账款 / 贷 6001 主营业务收入，金额=发票额，voucherDate=开票日期。
     *
     * @param invoice  已过账（COMPLETED）的销售发票
     * @param operator 操作人
     */
    public void generateForSalesInvoice(SalesInvoice invoice, String operator) {
        Objects.requireNonNull(invoice, "销售发票不能为空");
        BigDecimal amount = invoice.totalAmount();
        String summary = VoucherSourceType.SALES_INVOICE.label() + " " + invoice.getDocNo();
        generate(VoucherSourceType.SALES_INVOICE, invoice.getDocNo(), amount,
                invoice.getInvoiceDate(), summary,
                () -> List.of(
                        debitLine(ACC_RECEIVABLE, amount, summary),
                        creditLine(ACC_REVENUE, amount, summary)), operator);
    }

    /**
     * 收款单过账 → 凭证：借 glAccountCode（资金账户现金/银行科目）/ 贷 1122 应收账款，
     * 金额=收款总额，voucherDate=收款日期（M4-T04）。
     *
     * <p>现金侧借方科目<b>动态传入</b>（资金账户 {@code glAccountCode}，由 app 层取自
     * {@code PaymentAccount.getGlAccountCode()}），不写死 1001/1002（设计真源 §2.4）。
     * 由 {@code CollectionReceiptAppService.post} 在收款单过账 + 应收核销之后、同一 @Transactional
     * 内直调（核销 + 现金侧凭证 + 单据状态原子，任一失败整单回滚）。
     *
     * @param receipt       已过账（COMPLETED）的收款单
     * @param glAccountCode 资金账户映射的 GL 货币科目（借方，现金/银行）
     * @param operator      操作人（沿用业务过账操作人）
     */
    public void generateForCollectionReceipt(CollectionReceipt receipt, String glAccountCode,
                                             String operator) {
        Objects.requireNonNull(receipt, "收款单不能为空");
        Objects.requireNonNull(glAccountCode, "资金账户 GL 科目不能为空");
        BigDecimal amount = receipt.totalAmount();
        String summary = VoucherSourceType.COLLECTION_RECEIPT.label() + " " + receipt.getDocNo();
        generate(VoucherSourceType.COLLECTION_RECEIPT, receipt.getDocNo(), amount,
                receipt.getReceiptDate(), summary,
                () -> List.of(
                        debitLine(glAccountCode, amount, summary),
                        creditLine(ACC_RECEIVABLE, amount, summary)), operator);
    }

    /**
     * 付款单过账 → 凭证：借 220202 应付账款 / 贷 glAccountCode（资金账户现金/银行科目），
     * 金额=付款总额，voucherDate=付款日期（M4-T04）。
     *
     * <p>现金侧贷方科目<b>动态传入</b>（资金账户 {@code glAccountCode}），不写死 1001/1002
     * （设计真源 §2.4）。由 {@code PaymentDisbursementAppService.post} 在付款单过账 + 应付核销之后、
     * 同一 @Transactional 内直调（核销 + 现金侧凭证 + 单据状态原子，任一失败整单回滚）。
     *
     * @param disbursement  已过账（COMPLETED）的付款单
     * @param glAccountCode 资金账户映射的 GL 货币科目（贷方，现金/银行）
     * @param operator      操作人
     */
    public void generateForPaymentDisbursement(PaymentDisbursement disbursement, String glAccountCode,
                                               String operator) {
        Objects.requireNonNull(disbursement, "付款单不能为空");
        Objects.requireNonNull(glAccountCode, "资金账户 GL 科目不能为空");
        BigDecimal amount = disbursement.totalAmount();
        String summary = VoucherSourceType.PAYMENT_DISBURSEMENT.label() + " " + disbursement.getDocNo();
        generate(VoucherSourceType.PAYMENT_DISBURSEMENT, disbursement.getDocNo(), amount,
                disbursement.getPaymentDate(), summary,
                () -> List.of(
                        debitLine(ACC_PAYABLE_FORMAL, amount, summary),
                        creditLine(glAccountCode, amount, summary)), operator);
    }

    // ---------------------------------------------------------------
    // 统一生成流程（拆解 §2）：金额校验 → 幂等查重 → 账期保障 → 建草稿 → 过账
    // ---------------------------------------------------------------

    private void generate(VoucherSourceType sourceType, String sourceDocNo, BigDecimal amount,
                          LocalDate voucherDate, String summary, Supplier<List<VoucherLineInput>> linesSupplier,
                          String operator) {
        // ① 金额≤0 跳过（无金额无凭证；否则 Voucher.create「总额>0」会抛 VoucherNotBalancedException）
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        // ② 幂等查重：同来源单据已有凭证则跳过（每单一组、重过账/重试不重复，验收核心）
        if (!voucherService.findBySourceDocNo(sourceDocNo).isEmpty()) {
            return;
        }
        List<VoucherLineInput> lines = linesSupplier.get();
        // ③ 由凭证日期推算账期 + 生成 VCH- 号（按凭证日期所属年月段计序）
        YearMonth yearMonth = YearMonth.from(voucherDate);
        String period = yearMonth.format(PERIOD_FORMAT);
        // ④ 确保账期存在（不存在自动 open；CLOSED 留给 post 守卫抛 PeriodClosedException 回滚整个业务过账）
        ensurePeriodExists(period, operator);
        String docNo = numberGenerator.generate(VOUCHER_RULE, yearMonth);
        // ⑤ 建草稿（回填来源两列）→ 过账（DRAFT→APPROVED）；全程在业务 post 外层事务内
        voucherService.createFromSource(docNo, period, voucherDate, summary, sourceType, sourceDocNo,
                lines, operator);
        voucherService.post(docNo, operator);
    }

    /**
     * 确保账期存在：try get / catch NotFound → open（首次经营友好，拆解 §5）。
     * 账期已存在（无论 OPEN/CLOSED）不做任何处理——CLOSED 的关账守卫在 {@link VoucherService#post}。
     * 并发首单 open 撞 {@code uk_period} 唯一键时容错（撞键即视为已存在，小企业并发低，已知风险）。
     */
    /** 按采购入库行的商品存货分类汇总借方科目，并校验行金额与单据总额一致。 */
    private List<VoucherLineInput> purchaseReceiptLines(PurchaseReceipt receipt, BigDecimal expectedAmount,
                                                         String summary) {
        Map<String, BigDecimal> amountsByAccount = new LinkedHashMap<>();
        for (PurchaseReceiptLine line : Objects.requireNonNull(receipt.getLines(), "采购入库单行不能为空")) {
            addAmountByInventoryAccount(amountsByAccount, line.getProductId(), line.getAmount());
        }
        assertClassifiedTotal(receipt.getDocNo(), "采购入库", expectedAmount, amountsByAccount);

        List<VoucherLineInput> lines = new ArrayList<>();
        amountsByAccount.forEach((accountCode, amount) -> lines.add(debitLine(accountCode, amount, summary)));
        lines.add(creditLine(ACC_PAYABLE_ESTIMATED, expectedAmount, summary));
        return lines;
    }

    private List<VoucherLineInput> salesDeliveryLines(SalesDelivery delivery, BigDecimal expectedAmount,
                                                       String summary) {
        Map<String, BigDecimal> amountsByAccount = new LinkedHashMap<>();
        for (SalesDeliveryLine line : Objects.requireNonNull(delivery.getLines(), "销售出库单行不能为空")) {
            addAmountByInventoryAccount(amountsByAccount, line.getProductId(), line.getCogsAmount());
        }
        assertClassifiedTotal(delivery.getDocNo(), "销售出库", expectedAmount, amountsByAccount);

        List<VoucherLineInput> lines = new ArrayList<>();
        lines.add(debitLine(ACC_COGS, expectedAmount, summary));
        amountsByAccount.forEach((accountCode, amount) -> lines.add(creditLine(accountCode, amount, summary)));
        return lines;
    }

    private void addAmountByInventoryAccount(Map<String, BigDecimal> amountsByAccount, long productId,
                                              BigDecimal amount) {
        BigDecimal lineAmount = Objects.requireNonNull(amount, "已过账单据行成本不能为空");
        if (lineAmount.signum() < 0) {
            throw new IllegalArgumentException("已过账单据行成本不能为负: " + lineAmount.toPlainString());
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在: " + productId));
        String accountCode = inventoryAccountPolicy.accountFor(product.getInventoryCategory());
        amountsByAccount.merge(accountCode, lineAmount, BigDecimal::add);
    }

    private static void assertClassifiedTotal(String docNo, String documentType, BigDecimal expectedAmount,
                                              Map<String, BigDecimal> amountsByAccount) {
        BigDecimal classifiedTotal = amountsByAccount.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (classifiedTotal.compareTo(expectedAmount) != 0) {
            throw new IllegalStateException(documentType + "单据金额与行成本不一致，拒绝生成凭证: " + docNo
                    + "，单据=" + expectedAmount.toPlainString()
                    + "，行汇总=" + classifiedTotal.toPlainString());
        }
    }

    /** 确保凭证所属账期存在；不存在时首次自动开账，已关闭账期仍由凭证过账守卫拒绝。 */
    private void ensurePeriodExists(String period, String operator) {
        try {
            accountingPeriodService.get(period);
        } catch (AccountingPeriodNotFoundException notFound) {
            // 账期不存在：自动开启（首次经营友好，拆解 §5）。
            // 并发边界：两笔同时向同一新账期过账时，落后者的 open 会撞 uk_period 唯一键
            // （Spring DataIntegrityViolationException），该约束冲突污染当前事务、无法在事务内恢复，
            // 故落后者整笔业务过账回滚——可重试；小企业并发极低，作为已接受的已知风险（拆解 §8），
            // 不在事务内强行 catch 续跑（彼时事务已 rollback-only，续跑必失败）。
            accountingPeriodService.open(period, operator);
        }
    }

    private static VoucherLineInput debitLine(String accountCode, BigDecimal amount, String summary) {
        return new VoucherLineInput(accountCode, amount, BigDecimal.ZERO, summary);
    }

    private static VoucherLineInput creditLine(String accountCode, BigDecimal amount, String summary) {
        return new VoucherLineInput(accountCode, BigDecimal.ZERO, amount, summary);
    }
}
