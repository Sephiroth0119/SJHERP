package com.sjherp.app.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.sales.SalesDelivery;
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

    /** 1405 库存商品（资产/借） */
    private static final String ACC_INVENTORY = "1405";
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

    /** 凭证编号规则：VCH-202606-0001（与 {@link VoucherAppService#VOUCHER_RULE} 一致） */
    private static final DocumentNumberRule VOUCHER_RULE = DocumentNumberRule.of("VCH");

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final VoucherService voucherService;
    private final AccountingPeriodService accountingPeriodService;
    private final DocumentNumberGenerator numberGenerator;

    public AutoVoucherService(VoucherService voucherService,
                              AccountingPeriodService accountingPeriodService,
                              DocumentNumberGenerator numberGenerator) {
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.accountingPeriodService = Objects.requireNonNull(accountingPeriodService,
                "accountingPeriodService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
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
        List<VoucherLineInput> lines = List.of(
                debitLine(ACC_INVENTORY, amount, summary),
                creditLine(ACC_PAYABLE_ESTIMATED, amount, summary));
        generate(VoucherSourceType.PURCHASE_RECEIPT, receipt.getDocNo(), amount,
                receipt.getReceiptDate(), summary, lines, operator);
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
        List<VoucherLineInput> lines = List.of(
                debitLine(ACC_PAYABLE_ESTIMATED, amount, summary),
                creditLine(ACC_PAYABLE_FORMAL, amount, summary));
        generate(VoucherSourceType.PURCHASE_INVOICE, invoice.getDocNo(), amount,
                invoice.getInvoiceDate(), summary, lines, operator);
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
        List<VoucherLineInput> lines = List.of(
                debitLine(ACC_COGS, amount, summary),
                creditLine(ACC_INVENTORY, amount, summary));
        generate(VoucherSourceType.SALES_DELIVERY, delivery.getDocNo(), amount,
                voucherDate, summary, lines, operator);
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
        List<VoucherLineInput> lines = List.of(
                debitLine(ACC_RECEIVABLE, amount, summary),
                creditLine(ACC_REVENUE, amount, summary));
        generate(VoucherSourceType.SALES_INVOICE, invoice.getDocNo(), amount,
                invoice.getInvoiceDate(), summary, lines, operator);
    }

    // ---------------------------------------------------------------
    // 统一生成流程（拆解 §2）：金额校验 → 幂等查重 → 账期保障 → 建草稿 → 过账
    // ---------------------------------------------------------------

    private void generate(VoucherSourceType sourceType, String sourceDocNo, BigDecimal amount,
                          LocalDate voucherDate, String summary, List<VoucherLineInput> lines,
                          String operator) {
        // ① 金额≤0 跳过（无金额无凭证；否则 Voucher.create「总额>0」会抛 VoucherNotBalancedException）
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        // ② 幂等查重：同来源单据已有凭证则跳过（每单一组、重过账/重试不重复，验收核心）
        if (!voucherService.findBySourceDocNo(sourceDocNo).isEmpty()) {
            return;
        }
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
