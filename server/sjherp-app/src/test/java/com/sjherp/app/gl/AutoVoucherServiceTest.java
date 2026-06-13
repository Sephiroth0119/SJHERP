package com.sjherp.app.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesInvoice;

/**
 * 业务→凭证自动化引擎单测（M4-T02，拆解 §7，全系统最高风险的财务核心）。
 *
 * <p>Mockito mock 三依赖（VoucherService / AccountingPeriodService / DocumentNumberGenerator），
 * 用 {@link ArgumentCaptor} 捕获传给 {@link VoucherService#createFromSource} 的
 * {@code List<VoucherLineInput>}，逐类断言：
 * <ul>
 *   <li>四类分录的科目编码 / 借贷方向 / 金额正确（拆解 §1.2 分录表）；</li>
 *   <li>借贷平衡（Σ借==Σ贷）；</li>
 *   <li><b>幂等</b>：{@code findBySourceDocNo} 非空 → 永不 {@code createFromSource}/{@code post}（验收核心）；</li>
 *   <li>金额≤0 跳过（采购入库 totalAmount=0、销售出库 totalCogs=0 → 不查重不建单）；</li>
 *   <li>账期不存在（{@code get} 抛 NotFound）→ {@code open} 被调；CLOSED → post 抛异常外抛（不吞）；</li>
 *   <li>建草稿后立即过账（{@code createFromSource} → {@code post} 成对）。</li>
 * </ul>
 *
 * <p>所有金额用 {@link BigDecimal}（CLAUDE.md 原则 5：禁 float/double 参与金额运算）。
 */
class AutoVoucherServiceTest {

    private static final String OPERATOR = "tester";

    // 科目编码（拆解 §1.2，与 AutoVoucherService 类内常量一致）
    private static final String ACC_INVENTORY = "1405";
    private static final String ACC_PAYABLE_ESTIMATED = "220201";
    private static final String ACC_PAYABLE_FORMAL = "220202";
    private static final String ACC_COGS = "6401";
    private static final String ACC_RECEIVABLE = "1122";
    private static final String ACC_REVENUE = "6001";

    private VoucherService voucherService;
    private AccountingPeriodService accountingPeriodService;
    private DocumentNumberGenerator numberGenerator;
    private AutoVoucherService service;

    @BeforeEach
    void setUp() {
        voucherService = mock(VoucherService.class);
        accountingPeriodService = mock(AccountingPeriodService.class);
        numberGenerator = mock(DocumentNumberGenerator.class);
        service = new AutoVoucherService(voucherService, accountingPeriodService, numberGenerator);
        // 默认：来源单据尚无凭证（非幂等命中）+ 账期已存在（get 不抛）+ 编号生成
        when(voucherService.findBySourceDocNo(anyString())).thenReturn(List.of());
        when(numberGenerator.generate(any(), any())).thenReturn("VCH-202606-0001");
    }

    // ===================================================================
    // 一、四类分录：科目 / 借贷方向 / 金额 / 借贷平衡（拆解 §1.2）
    // ===================================================================

    @Test
    void 采购入库_借1405贷220201_金额等于入库成本_借贷平衡() {
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(receipt.getReceiptDate()).thenReturn(LocalDate.of(2026, 6, 13));

        service.generateForPurchaseReceipt(receipt, OPERATOR);

        List<VoucherLineInput> lines = captureLines("PR-202606-0001");
        assertDebit(lines, ACC_INVENTORY, "1800.00");
        assertCredit(lines, ACC_PAYABLE_ESTIMATED, "1800.00");
        assertBalanced(lines);
        verifyPostedAfterCreate();
    }

    @Test
    void 采购发票_借220201贷220202_金额等于发票额_借贷平衡() {
        PurchaseInvoice invoice = mock(PurchaseInvoice.class);
        when(invoice.getDocNo()).thenReturn("PINV-202606-0001");
        when(invoice.totalAmount()).thenReturn(new BigDecimal("1500.00"));
        when(invoice.getInvoiceDate()).thenReturn(LocalDate.of(2026, 6, 14));

        service.generateForPurchaseInvoice(invoice, OPERATOR);

        List<VoucherLineInput> lines = captureLines("PINV-202606-0001");
        assertDebit(lines, ACC_PAYABLE_ESTIMATED, "1500.00");
        assertCredit(lines, ACC_PAYABLE_FORMAL, "1500.00");
        assertBalanced(lines);
        verifyPostedAfterCreate();
    }

    @Test
    void 销售出库_借6401贷1405_金额等于COGS_借贷平衡() {
        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.getDocNo()).thenReturn("SD-202606-0001");
        when(delivery.totalCogs()).thenReturn(new BigDecimal("1200.00"));
        when(delivery.getCreatedAt()).thenReturn(LocalDate.of(2026, 6, 15)
                .atStartOfDay().toInstant(ZoneOffset.UTC));

        service.generateForSalesDelivery(delivery, OPERATOR);

        List<VoucherLineInput> lines = captureLines("SD-202606-0001");
        assertDebit(lines, ACC_COGS, "1200.00");
        assertCredit(lines, ACC_INVENTORY, "1200.00");
        assertBalanced(lines);
        verifyPostedAfterCreate();
    }

    @Test
    void 销售发票_借1122贷6001_金额等于发票额_借贷平衡() {
        SalesInvoice invoice = mock(SalesInvoice.class);
        when(invoice.getDocNo()).thenReturn("SINV-202606-0001");
        when(invoice.totalAmount()).thenReturn(new BigDecimal("2000.00"));
        when(invoice.getInvoiceDate()).thenReturn(LocalDate.of(2026, 6, 16));

        service.generateForSalesInvoice(invoice, OPERATOR);

        List<VoucherLineInput> lines = captureLines("SINV-202606-0001");
        assertDebit(lines, ACC_RECEIVABLE, "2000.00");
        assertCredit(lines, ACC_REVENUE, "2000.00");
        assertBalanced(lines);
        verifyPostedAfterCreate();
    }

    // ===================================================================
    // 二、来源类型 / 凭证日期 / 摘要 透传（可追溯锚点，拆解 §3）
    // ===================================================================

    @Test
    void 采购入库_来源类型与凭证日期与摘要正确透传() {
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0007");
        when(receipt.totalAmount()).thenReturn(new BigDecimal("100.00"));
        when(receipt.getReceiptDate()).thenReturn(LocalDate.of(2026, 6, 13));

        service.generateForPurchaseReceipt(receipt, OPERATOR);

        ArgumentCaptor<VoucherSourceType> typeCaptor = ArgumentCaptor.forClass(VoucherSourceType.class);
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(voucherService).createFromSource(anyString(), eq("202606"), dateCaptor.capture(),
                summaryCaptor.capture(), typeCaptor.capture(), eq("PR-202606-0007"), any(), eq(OPERATOR));
        assertThat(typeCaptor.getValue()).isEqualTo(VoucherSourceType.PURCHASE_RECEIPT);
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 6, 13));
        assertThat(summaryCaptor.getValue()).contains("采购入库").contains("PR-202606-0007");
    }

    @Test
    void 销售出库_无业务日_取过账日UTC推算账期() {
        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.getDocNo()).thenReturn("SD-NOW-0001");
        when(delivery.totalCogs()).thenReturn(new BigDecimal("50.00"));
        // 出库单无业务日：凭证日=过账日（now，UTC），账期=当月（不依赖脆弱的 createdAt 语义）
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String expectedPeriod = String.format("%04d%02d", today.getYear(), today.getMonthValue());

        service.generateForSalesDelivery(delivery, OPERATOR);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(voucherService).createFromSource(anyString(), eq(expectedPeriod), dateCaptor.capture(),
                anyString(), eq(VoucherSourceType.SALES_DELIVERY), eq("SD-NOW-0001"), any(),
                eq(OPERATOR));
        assertThat(dateCaptor.getValue()).isEqualTo(today);
    }

    // ===================================================================
    // 三、幂等（验收核心，拆解 §3）：findBySourceDocNo 非空 → 不建单不过账
    // ===================================================================

    @Test
    void 幂等_来源单据已有凭证_永不createFromSource与post() {
        // 同来源单据已生成凭证（findBySourceDocNo 返回非空）
        when(voucherService.findBySourceDocNo("PR-202606-0001"))
                .thenReturn(List.of(mock(com.sjherp.domain.gl.Voucher.class)));
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(receipt.getReceiptDate()).thenReturn(LocalDate.of(2026, 6, 13));

        service.generateForPurchaseReceipt(receipt, OPERATOR);

        verify(voucherService, never()).createFromSource(anyString(), anyString(), any(), anyString(),
                any(), anyString(), any(), anyString());
        verify(voucherService, never()).post(anyString(), anyString());
        // 编号/账期都不应触碰（幂等命中后早退）
        verify(numberGenerator, never()).generate(any(), any());
    }

    @Test
    void 幂等_四类各自按来源单号查重() {
        // 销售发票来源已存在 → 跳过
        when(voucherService.findBySourceDocNo("SINV-202606-0001"))
                .thenReturn(List.of(mock(com.sjherp.domain.gl.Voucher.class)));
        SalesInvoice invoice = mock(SalesInvoice.class);
        when(invoice.getDocNo()).thenReturn("SINV-202606-0001");
        when(invoice.totalAmount()).thenReturn(new BigDecimal("2000.00"));
        when(invoice.getInvoiceDate()).thenReturn(LocalDate.of(2026, 6, 16));

        service.generateForSalesInvoice(invoice, OPERATOR);

        verify(voucherService).findBySourceDocNo("SINV-202606-0001");
        verify(voucherService, never()).post(anyString(), anyString());
    }

    // ===================================================================
    // 四、金额≤0 跳过（拆解 §1.2 边界：无金额无凭证，否则 Voucher.create 抛不平衡）
    // ===================================================================

    @Test
    void 采购入库_金额为零_跳过_不查重不建单() {
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.totalAmount()).thenReturn(BigDecimal.ZERO);

        service.generateForPurchaseReceipt(receipt, OPERATOR);

        // 金额≤0 是第一道闸（早于幂等查重）：findBySourceDocNo / createFromSource / post 均不触碰
        verify(voucherService, never()).findBySourceDocNo(anyString());
        verify(voucherService, never()).createFromSource(anyString(), anyString(), any(), anyString(),
                any(), anyString(), any(), anyString());
        verify(voucherService, never()).post(anyString(), anyString());
    }

    @Test
    void 销售出库_COGS为零_跳过_不建单() {
        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.totalCogs()).thenReturn(BigDecimal.ZERO);
        // 真实出库单 createdAt 永不为 null（实现会先解析 voucherDate 再做 signum 判定）
        when(delivery.getCreatedAt()).thenReturn(Instant.now());

        service.generateForSalesDelivery(delivery, OPERATOR);

        verify(voucherService, never()).findBySourceDocNo(anyString());
        verify(voucherService, never()).post(anyString(), anyString());
    }

    @Test
    void 销售出库_COGS为负_跳过_不建单() {
        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.totalCogs()).thenReturn(new BigDecimal("-1.00"));
        when(delivery.getCreatedAt()).thenReturn(Instant.now());

        service.generateForSalesDelivery(delivery, OPERATOR);

        verify(voucherService, never()).findBySourceDocNo(anyString());
        verify(voucherService, never()).createFromSource(anyString(), anyString(), any(), anyString(),
                any(), anyString(), any(), anyString());
    }

    // ===================================================================
    // 五、账期处理（拆解 §5）
    // ===================================================================

    @Test
    void 账期不存在_自动open后继续建单过账() {
        // get 抛 NotFound（账期不存在）→ 触发 open
        when(accountingPeriodService.get("202606"))
                .thenThrow(new AccountingPeriodNotFoundException("202606"));
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(receipt.getReceiptDate()).thenReturn(LocalDate.of(2026, 6, 13));

        service.generateForPurchaseReceipt(receipt, OPERATOR);

        verify(accountingPeriodService).open("202606", OPERATOR);
        verify(voucherService).createFromSource(anyString(), eq("202606"), any(), anyString(),
                eq(VoucherSourceType.PURCHASE_RECEIPT), eq("PR-202606-0001"), any(), eq(OPERATOR));
        verify(voucherService).post(anyString(), eq(OPERATOR));
    }

    @Test
    void 账期已存在_不再open() {
        // get 正常返回（账期已存在，无论 OPEN/CLOSED 都不应 open）
        when(accountingPeriodService.get("202606"))
                .thenReturn(mock(com.sjherp.domain.gl.AccountingPeriod.class));
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(receipt.getReceiptDate()).thenReturn(LocalDate.of(2026, 6, 13));

        service.generateForPurchaseReceipt(receipt, OPERATOR);

        verify(accountingPeriodService, never()).open(anyString(), anyString());
        verify(voucherService).post(anyString(), eq(OPERATOR));
    }

    @Test
    void 账期已关账_post抛PeriodClosed_外抛不吞_回滚整个业务过账() {
        // 账期存在（CLOSED 不在 ensurePeriodExists 拦截）→ 建草稿后 post 守卫抛 PeriodClosedException
        when(accountingPeriodService.get("202606"))
                .thenReturn(mock(com.sjherp.domain.gl.AccountingPeriod.class));
        when(voucherService.post(anyString(), anyString()))
                .thenThrow(new PeriodClosedException("202606"));
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(receipt.getReceiptDate()).thenReturn(LocalDate.of(2026, 6, 13));

        // 异常必须外抛（不静默吞），由外层事务回滚整个业务过账（拆解 §5）
        assertThatThrownBy(() -> service.generateForPurchaseReceipt(receipt, OPERATOR))
                .isInstanceOf(PeriodClosedException.class);
        verify(accountingPeriodService, never()).reopen(anyString(), anyString());
    }

    // 注：并发首单 open 撞 uk_period 的场景，落后者会因约束冲突污染事务而整笔回滚（可重试），
    // 不在事务内强行恢复——属已接受的已知风险（拆解 §8），无法用单测有意义地覆盖（DB 级竞态），
    // 故不为其写"容错续跑"用例（那是错误语义）。

    // ===================================================================
    // 六、入参防御
    // ===================================================================

    @Test
    void 入参为空_抛NPE() {
        assertThatThrownBy(() -> service.generateForPurchaseReceipt(null, OPERATOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.generateForPurchaseInvoice(null, OPERATOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.generateForSalesDelivery(null, OPERATOR))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.generateForSalesInvoice(null, OPERATOR))
                .isInstanceOf(NullPointerException.class);
    }

    // ===================================================================
    // 工具：捕获行 / 断言科目方向金额 / 借贷平衡 / 建单即过账
    // ===================================================================

    /** 捕获传给 createFromSource 的凭证行（并校验来源单号匹配）。 */
    @SuppressWarnings("unchecked")
    private List<VoucherLineInput> captureLines(String expectedSourceDocNo) {
        ArgumentCaptor<List<VoucherLineInput>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createFromSource(anyString(), anyString(), any(), anyString(), any(),
                eq(expectedSourceDocNo), linesCaptor.capture(), anyString());
        return linesCaptor.getValue();
    }

    /** 断言存在某科目的借方行，金额相等且贷方为 0。 */
    private static void assertDebit(List<VoucherLineInput> lines, String accountCode, String amount) {
        VoucherLineInput line = lineOf(lines, accountCode);
        assertThat(line.debit()).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal(amount));
        assertThat(line.credit()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
    }

    /** 断言存在某科目的贷方行，金额相等且借方为 0。 */
    private static void assertCredit(List<VoucherLineInput> lines, String accountCode, String amount) {
        VoucherLineInput line = lineOf(lines, accountCode);
        assertThat(line.credit()).usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal(amount));
        assertThat(line.debit()).usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ZERO);
    }

    private static VoucherLineInput lineOf(List<VoucherLineInput> lines, String accountCode) {
        return lines.stream()
                .filter(l -> accountCode.equals(l.accountCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到科目行: " + accountCode + " in " + lines));
    }

    /** 借贷平衡：Σ借 == Σ贷（拆解 §1 借贷必平）。 */
    private static void assertBalanced(List<VoucherLineInput> lines) {
        BigDecimal totalDebit = lines.stream().map(VoucherLineInput::debit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lines.stream().map(VoucherLineInput::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).usingComparator(BigDecimal::compareTo).isEqualTo(totalCredit);
    }

    /** 建草稿后立即过账（createFromSource → post 成对，同来源单号、同操作人）。 */
    private void verifyPostedAfterCreate() {
        verify(voucherService).post(eq("VCH-202606-0001"), eq(OPERATOR));
    }
}
