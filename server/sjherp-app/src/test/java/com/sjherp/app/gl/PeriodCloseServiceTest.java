package com.sjherp.app.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckRunner;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyCheckType;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.gl.GlDtos.ClosingPreviewLine;
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountType;
import com.sjherp.domain.gl.AccountingPeriod;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.BalanceDirection;
import com.sjherp.domain.gl.PeriodStatus;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;

/**
 * {@link PeriodCloseService} 纯逻辑/行为单测（M4-T05，设计真源 docs/M4拆解-月末结转关账.md §1/§2.1）。
 *
 * <p>三组：
 * <ol>
 *   <li><b>损益结转算法</b>（§1，mock {@code VoucherService.trialBalance} + {@code AccountService.get}）：
 *       仅收入/仅费用/盈利/亏损/盈亏两平/损益全无发生额/多收入多费用/动态识别非预置 PROFIT_LOSS——
 *       断言结转行借贷方向与金额、4103 两腿额、netProfit、Σ借==Σ贷；</li>
 *   <li><b>close 步序行为</b>（§2.1 七步，mock 五依赖）：非 OPEN 拒、既存结转凭证拒、ERROR 闸门拒
 *       （reasons 非空）、仅 WARN 不阻塞、正常路径 createFromSource+post+close 次序与参数、结转后损益
 *       未归零抛 IllegalStateException；</li>
 *   <li><b>precheck 只读</b>（§2.1）：从不调 close/post/createFromSource、closeable 取值正确。</li>
 * </ol>
 *
 * <p>金额全程 BigDecimal/2 位字符串；账期键 yyyyMM、UTC（与生产一致）。
 */
@ExtendWith(MockitoExtension.class)
class PeriodCloseServiceTest {

    private static final String PERIOD = "202606";
    private static final String OPERATOR = "alice";
    private static final String ACC_RETAINED_PROFIT = "4103";

    @Mock
    private VoucherService voucherService;
    @Mock
    private AccountService accountService;
    @Mock
    private AccountingPeriodService accountingPeriodService;
    @Mock
    private ConsistencyCheckService consistencyCheckService;
    @Mock
    private ConsistencyCheckRunner consistencyCheckRunner;
    @Mock
    private DocumentNumberGenerator numberGenerator;

    private PeriodCloseService service;

    /** 测试用科目字典：buildClosingPlan 内 accountService.get(code) 据此返回；按需注册 */
    private Map<String, Account> accountStubs;

    @BeforeEach
    void setUp() {
        accountStubs = new HashMap<>();
        service = new PeriodCloseService(voucherService, accountService, accountingPeriodService,
                consistencyCheckService, numberGenerator);
        // 4103 本年利润恒可解析（结转目标常量）；4103 在多数算法用例被引用，lenient 避免未用例报 unnecessary
        registerAccount(ACC_RETAINED_PROFIT, "本年利润", AccountType.EQUITY, BalanceDirection.CREDIT);
        lenient().when(accountService.get(anyString()))
                .thenAnswer(inv -> {
                    String code = inv.getArgument(0);
                    Account account = accountStubs.get(code);
                    if (account == null) {
                        throw new IllegalStateException("测试未注册科目: " + code);
                    }
                    return account;
                });
    }

    // ===============================================================
    // 辅助：科目 / 余额 / 账期 / 报告
    // ===============================================================

    private void registerAccount(String code, String name, AccountType type, BalanceDirection dir) {
        Instant now = Instant.now();
        accountStubs.put(code, Account.restore(1L, code, name, type, dir, null, 1, true,
                true, true, OPERATOR, now, OPERATOR, now));
    }

    /** 注册一个损益类科目（参与结转） */
    private void profitLoss(String code, String name, BalanceDirection dir) {
        registerAccount(code, name, AccountType.PROFIT_LOSS, dir);
    }

    private static AccountBalance bal(String code, String debit, String credit) {
        return new AccountBalance(code, new BigDecimal(debit), new BigDecimal(credit));
    }

    private void stubTrialBalance(List<AccountBalance> balances) {
        when(voucherService.trialBalance(PERIOD)).thenReturn(balances);
    }

    private static AccountingPeriod openPeriod() {
        Instant now = Instant.now();
        return AccountingPeriod.restore(1L, PERIOD, 2026, 6, PeriodStatus.OPEN, null, null,
                OPERATOR, now, OPERATOR, now);
    }

    private static AccountingPeriod closedPeriod() {
        Instant now = Instant.now();
        return AccountingPeriod.restore(1L, PERIOD, 2026, 6, PeriodStatus.CLOSED, OPERATOR, now,
                OPERATOR, now, OPERATOR, now);
    }

    private static ConsistencyReport report(ConsistencyBreak... breaks) {
        return new ConsistencyReport(Instant.now(), List.of(breaks));
    }

    private static ConsistencyBreak errorBreak(String key, String message) {
        return new ConsistencyBreak(ConsistencyCheckType.LEDGER_QUANTITY, key, "100", "99",
                ConsistencySeverity.ERROR, message);
    }

    private static ConsistencyBreak warnBreak(String key, String message) {
        return new ConsistencyBreak(ConsistencyCheckType.SALES_THREE_WAY, key, "10", "12",
                ConsistencySeverity.WARN, message);
    }

    /** 从一次 createFromSource 调用捕获的 lines 中按方向求某科目的借/贷金额（首个匹配行）。 */
    private static BigDecimal lineDebit(List<VoucherLineInput> lines, String code) {
        return lines.stream().filter(l -> l.accountCode().equals(code)
                && l.debit() != null && l.debit().signum() > 0)
                .map(VoucherLineInput::debit).findFirst().orElse(null);
    }

    private static BigDecimal lineCredit(List<VoucherLineInput> lines, String code) {
        return lines.stream().filter(l -> l.accountCode().equals(code)
                && l.credit() != null && l.credit().signum() > 0)
                .map(VoucherLineInput::credit).findFirst().orElse(null);
    }

    private static BigDecimal sumDebit(List<VoucherLineInput> lines) {
        return lines.stream().map(l -> l.debit() == null ? BigDecimal.ZERO : l.debit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumCredit(List<VoucherLineInput> lines) {
        return lines.stream().map(l -> l.credit() == null ? BigDecimal.ZERO : l.credit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ===============================================================
    // 组 1：损益结转算法（通过 close 正常路径捕获结转凭证行落地，最贴近真实）
    // ===============================================================

    /**
     * 走 close 正常路径并捕获结转凭证 lines（结转后试算回放损益归零，断言不抛）。
     * @param prePostBalances     结转前 trialBalance（损益有发生额）
     * @param postBalancesZeroPl  结转后 trialBalance（损益已归零，Σ借==Σ贷），用于⑤断言
     * @return 捕获的结转凭证行 + 结果
     */
    private CloseCapture closeAndCapture(List<AccountBalance> prePostBalances,
                                         List<AccountBalance> postBalancesZeroPl) {
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        // ④ buildClosingPlan 读结转前，⑤ 断言读结转后：先返回 pre，再返回 post
        when(voucherService.trialBalance(PERIOD)).thenReturn(prePostBalances, postBalancesZeroPl);
        when(numberGenerator.generate(any(DocumentNumberRule.class), any(YearMonth.class)))
                .thenReturn("VCH-202606-0001");
        when(accountingPeriodService.close(PERIOD, OPERATOR)).thenReturn(closedPeriod());

        PeriodCloseResult result = service.close(PERIOD, OPERATOR);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VoucherLineInput>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(voucherService).createFromSource(eq("VCH-202606-0001"), eq(PERIOD), any(LocalDate.class),
                anyString(), eq(VoucherSourceType.PERIOD_CLOSING), eq(PERIOD), linesCaptor.capture(),
                eq(OPERATOR));
        return new CloseCapture(linesCaptor.getValue(), result);
    }

    private record CloseCapture(List<VoucherLineInput> lines, PeriodCloseResult result) {
    }

    /** 损益类科目结转后净额=0 的回放余额（科目借贷自平），供⑤断言通过。 */
    private static List<AccountBalance> zeroedPl(String... codes) {
        List<AccountBalance> list = new ArrayList<>();
        for (String code : codes) {
            list.add(bal(code, "0.00", "0.00"));
        }
        return list;
    }

    @Test
    void 仅收入_结转行为收入借4103贷_netProfit为正() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        // 收入贷方净额 1000：trialBalance 中 6001 贷 1000（净额 = 借−贷 = −1000）
        CloseCapture cap = closeAndCapture(
                List.of(bal("6001", "0.00", "1000.00")),
                zeroedPl("6001", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        // 收入类借方冲平 1000
        assertThat(lineDebit(lines, "6001")).isEqualByComparingTo("1000.00");
        // 4103 贷 1000（收入转入本年利润），无 4103 借行
        assertThat(lineCredit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("1000.00");
        assertThat(lineDebit(lines, ACC_RETAINED_PROFIT)).isNull();
        // Σ借==Σ贷
        assertThat(sumDebit(lines)).isEqualByComparingTo(sumCredit(lines));
        assertThat(cap.result().totalRevenue()).isEqualTo("1000.00");
        assertThat(cap.result().totalExpense()).isEqualTo("0.00");
        assertThat(cap.result().netProfit()).isEqualTo("1000.00");
    }

    @Test
    void 仅费用_结转行为费用贷4103借_netProfit为负() {
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        // 费用借方净额 600：trialBalance 中 6401 借 600（净额 = +600）
        CloseCapture cap = closeAndCapture(
                List.of(bal("6401", "600.00", "0.00")),
                zeroedPl("6401", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        // 费用类贷方冲平 600
        assertThat(lineCredit(lines, "6401")).isEqualByComparingTo("600.00");
        // 4103 借 600（费用转入本年利润），无 4103 贷行
        assertThat(lineDebit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("600.00");
        assertThat(lineCredit(lines, ACC_RETAINED_PROFIT)).isNull();
        assertThat(sumDebit(lines)).isEqualByComparingTo(sumCredit(lines));
        assertThat(cap.result().netProfit()).isEqualTo("-600.00");
    }

    @Test
    void 盈利_收入大于费用_4103两腿_netProfit为正() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        // 收入 1000、费用 600 → 净利润 +400
        CloseCapture cap = closeAndCapture(
                List.of(bal("6001", "0.00", "1000.00"), bal("6401", "600.00", "0.00")),
                zeroedPl("6001", "6401", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        assertThat(lineDebit(lines, "6001")).isEqualByComparingTo("1000.00");
        assertThat(lineCredit(lines, "6401")).isEqualByComparingTo("600.00");
        // 4103 两腿都出：贷 1000（收入）、借 600（费用）
        assertThat(lineCredit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("1000.00");
        assertThat(lineDebit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("600.00");
        assertThat(sumDebit(lines)).isEqualByComparingTo(sumCredit(lines));
        assertThat(cap.result().totalRevenue()).isEqualTo("1000.00");
        assertThat(cap.result().totalExpense()).isEqualTo("600.00");
        assertThat(cap.result().netProfit()).isEqualTo("400.00");
    }

    @Test
    void 亏损_费用大于收入_netProfit为负() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        // 收入 500、费用 800 → 净利润 −300
        CloseCapture cap = closeAndCapture(
                List.of(bal("6001", "0.00", "500.00"), bal("6401", "800.00", "0.00")),
                zeroedPl("6001", "6401", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        assertThat(lineCredit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("500.00");
        assertThat(lineDebit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("800.00");
        assertThat(sumDebit(lines)).isEqualByComparingTo(sumCredit(lines));
        assertThat(cap.result().netProfit()).isEqualTo("-300.00");
    }

    @Test
    void 盈亏两平_4103借贷各一行_netProfit为零() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        // 收入 700、费用 700 → 净利润 0，但 4103 两腿仍各出（毛额透明，拆解 §1 边界）
        CloseCapture cap = closeAndCapture(
                List.of(bal("6001", "0.00", "700.00"), bal("6401", "700.00", "0.00")),
                zeroedPl("6001", "6401", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        assertThat(lineCredit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("700.00");
        assertThat(lineDebit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("700.00");
        assertThat(sumDebit(lines)).isEqualByComparingTo(sumCredit(lines));
        assertThat(cap.result().netProfit()).isEqualTo("0.00");
        assertThat(cap.result().closingVoucherDocNo()).isEqualTo("VCH-202606-0001");
    }

    @Test
    void 多收入多费用科目_全部纳入结转_4103汇总两腿() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        profitLoss("6051", "其他业务收入", BalanceDirection.CREDIT);
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        profitLoss("6602", "管理费用", BalanceDirection.DEBIT);
        // 收入 1000+200=1200、费用 600+150=750 → 净利润 450
        CloseCapture cap = closeAndCapture(
                List.of(bal("6001", "0.00", "1000.00"), bal("6051", "0.00", "200.00"),
                        bal("6401", "600.00", "0.00"), bal("6602", "150.00", "0.00")),
                zeroedPl("6001", "6051", "6401", "6602", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        assertThat(lineDebit(lines, "6001")).isEqualByComparingTo("1000.00");
        assertThat(lineDebit(lines, "6051")).isEqualByComparingTo("200.00");
        assertThat(lineCredit(lines, "6401")).isEqualByComparingTo("600.00");
        assertThat(lineCredit(lines, "6602")).isEqualByComparingTo("150.00");
        // 4103 贷 = Σ收入 1200、借 = Σ费用 750
        assertThat(lineCredit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("1200.00");
        assertThat(lineDebit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("750.00");
        assertThat(sumDebit(lines)).isEqualByComparingTo(sumCredit(lines));
        assertThat(cap.result().netProfit()).isEqualTo("450.00");
    }

    @Test
    void 动态识别_非预置损益科目也纳入结转() {
        // 构造一个非预置（is_preset=false）、自定义编码的 PROFIT_LOSS 科目，验证按类别动态识别而非硬编码清单
        Instant now = Instant.now();
        accountStubs.put("9999", Account.restore(2L, "9999", "自定义损益科目",
                AccountType.PROFIT_LOSS, BalanceDirection.CREDIT, null, 1, true,
                true, false, OPERATOR, now, OPERATOR, now));
        CloseCapture cap = closeAndCapture(
                List.of(bal("9999", "0.00", "320.00")),
                zeroedPl("9999", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        // 自定义损益科目被纳入：贷方净额 320 → 借方冲平 320
        assertThat(lineDebit(lines, "9999")).isEqualByComparingTo("320.00");
        assertThat(lineCredit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("320.00");
        assertThat(cap.result().totalRevenue()).isEqualTo("320.00");
    }

    @Test
    void 非损益科目_资产负债成本_不参与结转() {
        // 资产 1001、负债 2202、成本 5001 均有发生额，但都不应进结转凭证（仅 PROFIT_LOSS 参与）
        registerAccount("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT);
        registerAccount("2202", "应付账款", AccountType.LIABILITY, BalanceDirection.CREDIT);
        registerAccount("5001", "生产成本", AccountType.COST, BalanceDirection.DEBIT);
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        // 结转前整表 Σ借==Σ贷（6000=6000）：仅 6001 损益参与结转；资产/负债/成本不进结转凭证
        CloseCapture cap = closeAndCapture(
                List.of(bal("1001", "4000.00", "0.00"), bal("2202", "0.00", "5000.00"),
                        bal("5001", "2000.00", "0.00"), bal("6001", "0.00", "1000.00")),
                // ⑤ 断言只检查 PROFIT_LOSS 科目归零（6001→0），但整表仍须 Σ借==Σ贷（4103 承接 1000 贷）
                List.of(bal("1001", "4000.00", "0.00"), bal("2202", "0.00", "5000.00"),
                        bal("5001", "2000.00", "0.00"), bal("6001", "0.00", "0.00"),
                        bal(ACC_RETAINED_PROFIT, "0.00", "1000.00")));

        List<VoucherLineInput> lines = cap.lines();
        // 仅 6001（损益）与 4103 出现；1001/2202/5001 不在结转凭证里
        assertThat(lines).allSatisfy(l -> assertThat(l.accountCode())
                .isIn("6001", ACC_RETAINED_PROFIT));
        assertThat(cap.result().totalRevenue()).isEqualTo("1000.00");
        assertThat(cap.result().totalExpense()).isEqualTo("0.00");
    }

    @Test
    void 损益全无发生额_不生成结转凭证_docNo为null_但仍可关账() {
        // 只有非损益科目有发生额（损益类无发生额或本就为 0）→ plan.lines 空 → 不建凭证
        registerAccount("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        // 结转前后均无损益发生额；试算两次都返回平衡的非损益态
        List<AccountBalance> balances = List.of(bal("1001", "5000.00", "5000.00"));
        when(voucherService.trialBalance(PERIOD)).thenReturn(balances, balances);
        when(accountingPeriodService.close(PERIOD, OPERATOR)).thenReturn(closedPeriod());

        PeriodCloseResult result = service.close(PERIOD, OPERATOR);

        // 无结转凭证：不调编号生成、不调 createFromSource、不调 post
        assertThat(result.closingVoucherDocNo()).isNull();
        verify(numberGenerator, never()).generate(any(), any());
        verify(voucherService, never()).createFromSource(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(voucherService, never()).post(anyString(), anyString());
        // 但仍关账成功
        verify(accountingPeriodService).close(PERIOD, OPERATOR);
        assertThat(result.netProfit()).isEqualTo("0.00");
        assertThat(result.period()).isEqualTo(PERIOD);
    }

    @Test
    void 损益净额为零的科目跳过_compareTo口径不受标度影响() {
        // 6001 贷 1000、借 1000（净额 0，本期无净发生额）→ 跳过该科目；6401 净额 600 进结转
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        CloseCapture cap = closeAndCapture(
                List.of(bal("6001", "1000.00", "1000.00"), bal("6401", "600.00", "0.00")),
                zeroedPl("6001", "6401", ACC_RETAINED_PROFIT));

        List<VoucherLineInput> lines = cap.lines();
        // 6001 净额 0 被跳过：结转凭证中不含 6001 行
        assertThat(lines).noneSatisfy(l -> assertThat(l.accountCode()).isEqualTo("6001"));
        // 仅费用 6401 与 4103 借
        assertThat(lineCredit(lines, "6401")).isEqualByComparingTo("600.00");
        assertThat(lineDebit(lines, ACC_RETAINED_PROFIT)).isEqualByComparingTo("600.00");
        assertThat(cap.result().totalRevenue()).isEqualTo("0.00");
        assertThat(cap.result().totalExpense()).isEqualTo("600.00");
    }

    // ===============================================================
    // 组 2：close 步序行为（七步闸门与调用次序）
    // ===============================================================

    @Test
    void close_账期非OPEN_抛PeriodCloseBlocked_不结转不关账() {
        when(accountingPeriodService.get(PERIOD)).thenReturn(closedPeriod());

        assertThatThrownBy(() -> service.close(PERIOD, OPERATOR))
                .isInstanceOf(PeriodCloseBlockedException.class)
                .hasMessageContaining("OPEN");

        // ① 闸门即拒：不查既存结转凭证、不跑一致性、不建/过账、不关账
        verify(voucherService, never()).findBySourceDocNo(anyString());
        verify(consistencyCheckService, never()).check();
        verify(voucherService, never()).createFromSource(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(accountingPeriodService, never()).close(anyString(), anyString());
    }

    @Test
    void close_已存在结转凭证_抛PeriodCloseBlocked_提示先冲销() {
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        // ② findBySourceDocNo 非空 = 重开后重结场景，硬拒
        com.sjherp.domain.gl.Voucher existing = Mockito.mock(com.sjherp.domain.gl.Voucher.class);
        when(existing.getDocNo()).thenReturn("VCH-202606-0009");
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.close(PERIOD, OPERATOR))
                .isInstanceOf(PeriodCloseBlockedException.class)
                .hasMessageContaining("VCH-202606-0009");

        // 不跑一致性、不再建结转、不关账
        verify(consistencyCheckService, never()).check();
        verify(voucherService, never()).createFromSource(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(accountingPeriodService, never()).close(anyString(), anyString());
    }

    @Test
    void close_一致性含ERROR_抛PeriodCloseBlocked_reasons非空且不关账() {
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report(
                errorBreak("warehouse=1,product=2", "库存数量恒等式破坏"),
                errorBreak("PI-202606-0001", "应付金额勾稽不平")));

        assertThatThrownBy(() -> service.close(PERIOD, OPERATOR))
                .isInstanceOf(PeriodCloseBlockedException.class)
                .satisfies(ex -> {
                    PeriodCloseBlockedException blocked = (PeriodCloseBlockedException) ex;
                    // reasons 携带两条 ERROR 摘要，供 Agent/向导复述
                    assertThat(blocked.getReasons()).hasSize(2);
                    assertThat(blocked.getReasons().get(0)).contains("LEDGER_QUANTITY");
                });

        // ERROR 闸门拒：不建结转、不关账
        verify(voucherService, never()).createFromSource(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(accountingPeriodService, never()).close(anyString(), anyString());
    }

    @Test
    void close_仅WARN不阻塞_正常结转关账() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        // 仅 WARN（三单数量越界），无 ERROR → 不阻塞关账
        when(consistencyCheckService.check()).thenReturn(report(
                warnBreak("SO-202606-0001", "已开票量超过已发量")));
        when(voucherService.trialBalance(PERIOD)).thenReturn(
                List.of(bal("6001", "0.00", "1000.00")),
                zeroedPl("6001", ACC_RETAINED_PROFIT));
        when(numberGenerator.generate(any(DocumentNumberRule.class), any(YearMonth.class)))
                .thenReturn("VCH-202606-0001");
        when(accountingPeriodService.close(PERIOD, OPERATOR)).thenReturn(closedPeriod());

        PeriodCloseResult result = service.close(PERIOD, OPERATOR);

        // WARN 不阻塞：结转 + 关账正常
        verify(voucherService).createFromSource(eq("VCH-202606-0001"), eq(PERIOD), any(), anyString(),
                eq(VoucherSourceType.PERIOD_CLOSING), eq(PERIOD), any(), eq(OPERATOR));
        verify(voucherService).post("VCH-202606-0001", OPERATOR);
        verify(accountingPeriodService).close(PERIOD, OPERATOR);
        assertThat(result.closingVoucherDocNo()).isEqualTo("VCH-202606-0001");
    }

    @Test
    void close_正常路径_createFromSource_post_close次序正确() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        when(voucherService.trialBalance(PERIOD)).thenReturn(
                List.of(bal("6001", "0.00", "1000.00"), bal("6401", "600.00", "0.00")),
                zeroedPl("6001", "6401", ACC_RETAINED_PROFIT));
        when(numberGenerator.generate(any(DocumentNumberRule.class), any(YearMonth.class)))
                .thenReturn("VCH-202606-0001");
        when(accountingPeriodService.close(PERIOD, OPERATOR)).thenReturn(closedPeriod());

        PeriodCloseResult result = service.close(PERIOD, OPERATOR);

        // 次序：createFromSource → post → accountingPeriodService.close（结转必在关账前，账期仍 OPEN）
        InOrder inOrder = Mockito.inOrder(voucherService, accountingPeriodService);
        inOrder.verify(voucherService).createFromSource(eq("VCH-202606-0001"), eq(PERIOD),
                any(LocalDate.class), anyString(), eq(VoucherSourceType.PERIOD_CLOSING), eq(PERIOD),
                any(), eq(OPERATOR));
        inOrder.verify(voucherService).post("VCH-202606-0001", OPERATOR);
        inOrder.verify(accountingPeriodService).close(PERIOD, OPERATOR);

        // 结转凭证日期为账期末日 2026-06-30
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(voucherService).createFromSource(anyString(), anyString(), dateCaptor.capture(),
                anyString(), any(), anyString(), any(), anyString());
        assertThat(dateCaptor.getValue()).isEqualTo(YearMonth.of(2026, 6).atEndOfMonth());

        // 结果含关账人/时间（来自 close 返回的 CLOSED 账期）
        assertThat(result.closedBy()).isEqualTo(OPERATOR);
        assertThat(result.closedAt()).isNotNull();
        assertThat(result.period()).isEqualTo(PERIOD);
    }

    @Test
    void close_结转凭证编号按账期年月段生成() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        when(voucherService.trialBalance(PERIOD)).thenReturn(
                List.of(bal("6001", "0.00", "1000.00")),
                zeroedPl("6001", ACC_RETAINED_PROFIT));
        when(numberGenerator.generate(any(DocumentNumberRule.class), any(YearMonth.class)))
                .thenReturn("VCH-202606-0001");
        when(accountingPeriodService.close(PERIOD, OPERATOR)).thenReturn(closedPeriod());

        service.close(PERIOD, OPERATOR);

        // 编号年月段取自账期键解析的 YearMonth（202606）
        ArgumentCaptor<DocumentNumberRule> ruleCaptor =
                ArgumentCaptor.forClass(DocumentNumberRule.class);
        ArgumentCaptor<YearMonth> ymCaptor = ArgumentCaptor.forClass(YearMonth.class);
        verify(numberGenerator).generate(ruleCaptor.capture(), ymCaptor.capture());
        assertThat(ruleCaptor.getValue().getPrefix()).isEqualTo("VCH");
        assertThat(ymCaptor.getValue()).isEqualTo(YearMonth.of(2026, 6));
    }

    @Test
    void close_结转后损益未归零_抛IllegalState_回滚() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        // 结转前 6001 贷 1000；结转后 mock 返回异常态：6001 仍残留贷 1000（损益未归零，⑤断言失败）
        when(voucherService.trialBalance(PERIOD)).thenReturn(
                List.of(bal("6001", "0.00", "1000.00")),
                // 结转后回放：6001 借贷不等（净额 −1000≠0）但全表 Σ借==Σ贷（4103 顶上）
                List.of(bal("6001", "0.00", "1000.00"), bal(ACC_RETAINED_PROFIT, "1000.00", "0.00")));
        when(numberGenerator.generate(any(DocumentNumberRule.class), any(YearMonth.class)))
                .thenReturn("VCH-202606-0001");

        assertThatThrownBy(() -> service.close(PERIOD, OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PeriodCloseBlockedException.class)
                .hasMessageContaining("6001");

        // ⑤ 断言失败在 close（⑥）之前 → 不关账（整事务回滚）
        verify(accountingPeriodService, never()).close(anyString(), anyString());
    }

    @Test
    void close_结转后试算不平_抛IllegalState_回滚() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        // 结转后 Σ借≠Σ贷（防御性断言兜底）
        when(voucherService.trialBalance(PERIOD)).thenReturn(
                List.of(bal("6001", "0.00", "1000.00")),
                List.of(bal("6001", "0.00", "0.00"), bal(ACC_RETAINED_PROFIT, "999.00", "0.00")));
        when(numberGenerator.generate(any(DocumentNumberRule.class), any(YearMonth.class)))
                .thenReturn("VCH-202606-0001");

        assertThatThrownBy(() -> service.close(PERIOD, OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不平");

        verify(accountingPeriodService, never()).close(anyString(), anyString());
    }

    @Test
    void close_operator为空_抛IllegalArgument_不触碰任何依赖() {
        assertThatThrownBy(() -> service.close(PERIOD, "  "))
                .isInstanceOf(IllegalArgumentException.class);

        verify(accountingPeriodService, never()).get(anyString());
        verify(voucherService, never()).findBySourceDocNo(anyString());
    }

    @Test
    void close_账期不存在_透传AccountingPeriodNotFound() {
        when(accountingPeriodService.get(PERIOD))
                .thenThrow(new com.sjherp.domain.gl.AccountingPeriodNotFoundException(PERIOD));

        assertThatThrownBy(() -> service.close(PERIOD, OPERATOR))
                .isInstanceOf(com.sjherp.domain.gl.AccountingPeriodNotFoundException.class);

        verify(voucherService, never()).findBySourceDocNo(anyString());
    }

    // ===============================================================
    // 组 3：precheck 只读（不触发任何写）
    // ===============================================================

    @Test
    void precheck_从不调close_post_createFromSource() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        when(voucherService.trialBalance(PERIOD))
                .thenReturn(List.of(bal("6001", "0.00", "1000.00")));

        service.precheck(PERIOD);

        // 只读铁律：绝不调任何写口
        verify(accountingPeriodService, never()).close(anyString(), anyString());
        verify(voucherService, never()).post(anyString(), anyString());
        verify(voucherService, never()).createFromSource(any(), any(), any(), any(), any(), any(),
                any(), any());
        verify(numberGenerator, never()).generate(any(), any());
    }

    @Test
    void precheck_继续直接调用纯校验服务_不产生运行报告() {
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        when(voucherService.trialBalance(PERIOD)).thenReturn(List.of());

        service.precheck(PERIOD);

        verify(consistencyCheckService).check();
        verifyNoInteractions(consistencyCheckRunner);
    }

    @Test
    void precheck_OPEN且无既存结转且无ERROR_closeable为true_含结转预览净利润() {
        profitLoss("6001", "主营业务收入", BalanceDirection.CREDIT);
        profitLoss("6401", "主营业务成本", BalanceDirection.DEBIT);
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report(
                warnBreak("SO-1", "仅 WARN 不影响 closeable")));
        when(voucherService.trialBalance(PERIOD))
                .thenReturn(List.of(bal("6001", "0.00", "1000.00"), bal("6401", "600.00", "0.00")));

        PeriodCloseReadiness readiness = service.precheck(PERIOD);

        assertThat(readiness.closeable()).isTrue();
        assertThat(readiness.status()).isEqualTo("OPEN");
        assertThat(readiness.alreadyClosed()).isFalse();
        assertThat(readiness.consistencyErrors()).isEmpty();
        assertThat(readiness.consistencyWarnings()).hasSize(1);
        // 结转预览含 6001/6401/4103 行
        assertThat(readiness.closingPreviewLines())
                .extracting(ClosingPreviewLine::accountCode)
                .contains("6001", "6401", ACC_RETAINED_PROFIT);
        assertThat(readiness.totalRevenue()).isEqualTo("1000.00");
        assertThat(readiness.totalExpense()).isEqualTo("600.00");
        assertThat(readiness.netProfit()).isEqualTo("400.00");
        // 试算平衡 Σ借/Σ贷（当前已过账态，6001 贷 1000 + 6401 借 600）
        assertThat(readiness.trialBalanceDebit()).isEqualTo("600.00");
        assertThat(readiness.trialBalanceCredit()).isEqualTo("1000.00");
    }

    @Test
    void precheck_账期已CLOSED_closeable为false() {
        when(accountingPeriodService.get(PERIOD)).thenReturn(closedPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report());
        when(voucherService.trialBalance(PERIOD)).thenReturn(List.of());

        PeriodCloseReadiness readiness = service.precheck(PERIOD);

        assertThat(readiness.closeable()).isFalse();
        assertThat(readiness.status()).isEqualTo("CLOSED");
    }

    @Test
    void precheck_存在ERROR_closeable为false_errors非空() {
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of());
        when(consistencyCheckService.check()).thenReturn(report(
                errorBreak("warehouse=1,product=2", "库存账实不平")));
        when(voucherService.trialBalance(PERIOD)).thenReturn(List.of());

        PeriodCloseReadiness readiness = service.precheck(PERIOD);

        assertThat(readiness.closeable()).isFalse();
        assertThat(readiness.consistencyErrors()).hasSize(1);
    }

    @Test
    void precheck_已存在结转凭证_alreadyClosed为true_closeable为false() {
        when(accountingPeriodService.get(PERIOD)).thenReturn(openPeriod());
        com.sjherp.domain.gl.Voucher existing = Mockito.mock(com.sjherp.domain.gl.Voucher.class);
        when(voucherService.findBySourceDocNo(PERIOD)).thenReturn(List.of(existing));
        when(consistencyCheckService.check()).thenReturn(report());
        when(voucherService.trialBalance(PERIOD)).thenReturn(List.of());

        PeriodCloseReadiness readiness = service.precheck(PERIOD);

        assertThat(readiness.alreadyClosed()).isTrue();
        assertThat(readiness.closeable()).isFalse();
        // precheck 只读，不调 getDocNo 也不抛——这里仅断言读到的可关性
        verify(consistencyCheckService, atLeastOnce()).check();
    }
}
