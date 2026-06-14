package com.sjherp.app.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.sjherp.app.finance.FinancialStatementDao.AccountNetRow;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheetLine;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatementLine;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountType;
import com.sjherp.domain.gl.BalanceDirection;

/**
 * {@link FinancialStatementService} 单元测试（M4-T06，mock {@link FinancialStatementDao}
 * + {@link AccountService}）。验证设计真源 §2 的科目映射归集、平衡校验、净利润计算口径。
 *
 * <p>覆盖：资产负债表科目合并（货币资金 1001/1002/1012、存货含 contra 1471 减项、固定资产减
 * 累计折旧 1602、应付=220201+220202、应交税费=222101+222102）；构造平衡数据 balanced=true；
 * 未结转损益折入权益使<b>开账期</b>（损益类有余额未结转）也平衡；未映射有余额科目落"其他"行不丢；
 * 利润表各行映射 + 营业利润/利润总额/净利润计算 + 收入贷方向·费用借方向净额；本期 vs 本年累计
 * 调 DAO 区间不同（[P,P] 与 [yyyy01,P]）。
 */
class FinancialStatementServiceTest {

    private static final String PERIOD = "202606";
    private static final String YEAR_START = "202601";

    private FinancialStatementDao dao;
    private AccountService accountService;
    private FinancialStatementService service;

    @BeforeEach
    void setUp() {
        dao = Mockito.mock(FinancialStatementDao.class);
        accountService = Mockito.mock(AccountService.class);
        service = new FinancialStatementService(dao, accountService);
    }

    // ================================================================ 夹具

    /** 还原一个科目（仅 code/name/type/balanceDir 影响报表归集，其余取默认）。 */
    private static Account acct(String code, AccountType type, BalanceDirection dir) {
        return Account.restore(code.hashCode() & 0x7fffffff, code, code, type, dir,
                null, 1, true, true, true, "sys", Instant.EPOCH, "sys", Instant.EPOCH);
    }

    /** 一行净额原料（debit/credit 字符串）。 */
    private static AccountNetRow row(String code, String debit, String credit) {
        return new AccountNetRow(code, new BigDecimal(debit), new BigDecimal(credit));
    }

    /** 在科目表注册标准预置科目元数据（仅本测试涉及的科目）。 */
    private void stubAccounts(Account... extra) {
        List<Account> all = new ArrayList<>(List.of(
                // 资产
                acct("1001", AccountType.ASSET, BalanceDirection.DEBIT),
                acct("1002", AccountType.ASSET, BalanceDirection.DEBIT),
                acct("1012", AccountType.ASSET, BalanceDirection.DEBIT),
                acct("1122", AccountType.ASSET, BalanceDirection.DEBIT),
                acct("1405", AccountType.ASSET, BalanceDirection.DEBIT),
                acct("1471", AccountType.ASSET, BalanceDirection.CREDIT), // 存货跌价准备 contra
                acct("1601", AccountType.ASSET, BalanceDirection.DEBIT),
                acct("1602", AccountType.ASSET, BalanceDirection.CREDIT), // 累计折旧 contra
                // 负债
                acct("220201", AccountType.LIABILITY, BalanceDirection.CREDIT),
                acct("220202", AccountType.LIABILITY, BalanceDirection.CREDIT),
                acct("222101", AccountType.LIABILITY, BalanceDirection.CREDIT),
                acct("222102", AccountType.LIABILITY, BalanceDirection.CREDIT),
                // 权益
                acct("4001", AccountType.EQUITY, BalanceDirection.CREDIT),
                acct("4103", AccountType.EQUITY, BalanceDirection.CREDIT),
                acct("4104", AccountType.EQUITY, BalanceDirection.CREDIT),
                // 损益/成本
                acct("6001", AccountType.PROFIT_LOSS, BalanceDirection.CREDIT),
                acct("6051", AccountType.PROFIT_LOSS, BalanceDirection.CREDIT),
                acct("6111", AccountType.PROFIT_LOSS, BalanceDirection.CREDIT),
                acct("6301", AccountType.PROFIT_LOSS, BalanceDirection.CREDIT),
                acct("6401", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT), // 主营业务成本（V19 为损益类，非成本类）
                acct("6402", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT), // 其他业务成本（同上）
                acct("6403", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT),
                acct("6601", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT),
                acct("6602", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT),
                acct("6603", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT),
                acct("6711", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT),
                acct("6801", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT)));
        all.addAll(List.of(extra));
        Mockito.when(accountService.listAll()).thenReturn(all);
    }

    /** 从报表行列表里取某行金额（找不到返回 null）。 */
    private static String amountOf(List<BalanceSheetLine> lines, String name) {
        return lines.stream().filter(l -> l.name().equals(name)).map(BalanceSheetLine::amount)
                .findFirst().orElse(null);
    }

    private static IncomeStatementLine isLine(IncomeStatement is, String name) {
        return is.lines().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow();
    }

    // ================================================================ 1. 资产负债表 — 科目映射

    /**
     * 货币资金合并 1001/1002/1012；存货含 contra 1471 减项；固定资产减累计折旧 1602；
     * 应付=220201+220202；应交税费=222101+222102。逐行核对归集金额。
     */
    @Test
    void 资产负债表_科目映射归集_合并与contra减项正确() {
        stubAccounts();
        Mockito.when(dao.cumulativeBalances(PERIOD)).thenReturn(List.of(
                // 货币资金：1001=100 + 1002=200 + 1012=300 = 600
                row("1001", "100", "0"), row("1002", "200", "0"), row("1012", "300", "0"),
                // 存货：1405=1000 借，1471=80 贷（contra，net=-80）→ 920
                row("1405", "1000", "0"), row("1471", "0", "80"),
                // 固定资产：1601=5000 借，1602=1200 贷（累计折旧 contra，net=-1200）→ 3800
                row("1601", "5000", "0"), row("1602", "0", "1200")));

        BalanceSheet bs = service.balanceSheet(PERIOD);

        assertEquals("600", amountOf(bs.assetLines(), "货币资金"));
        assertEquals("920", amountOf(bs.assetLines(), "存货"));
        assertEquals("3800", amountOf(bs.assetLines(), "固定资产"));
    }

    /** 应付账款=220201+220202、应交税费=222101+222102（负债贷方向净额 = −借方向净额）。 */
    @Test
    void 资产负债表_负债合并_应付与应交税费按贷方向() {
        stubAccounts();
        Mockito.when(dao.cumulativeBalances(PERIOD)).thenReturn(List.of(
                // 应付：220201=300 贷 + 220202=700 贷 = 1000（贷方向为正）
                row("220201", "0", "300"), row("220202", "0", "700"),
                // 应交税费：222101=50 贷 + 222102=30 贷 = 80
                row("222101", "0", "50"), row("222102", "0", "30")));

        BalanceSheet bs = service.balanceSheet(PERIOD);

        assertEquals("1000", amountOf(bs.liabilityLines(), "应付账款"));
        assertEquals("80", amountOf(bs.liabilityLines(), "应交税费"));
    }

    // ================================================================ 2. 资产负债表 — 平衡

    /**
     * 关账后场景（损益类已归零）：资产 = 负债 + 权益，balanced=true。
     * 货币资金 1000（借）= 实收资本 1000（贷）。
     */
    @Test
    void 资产负债表_平衡数据_balanced为true() {
        stubAccounts();
        Mockito.when(dao.cumulativeBalances(PERIOD)).thenReturn(List.of(
                row("1001", "1000", "0"),
                row("4001", "0", "1000")));

        BalanceSheet bs = service.balanceSheet(PERIOD);

        assertEquals("1000", bs.totalAssets());
        assertEquals("0", bs.totalLiabilities());
        assertEquals("1000", bs.totalEquity());
        assertTrue(bs.balanced(), "资产=负债+权益，应平衡");
    }

    /**
     * 开账期（损益类有余额、尚未结转）也必须平衡——靠"本期未结转损益"折入未分配利润。
     * 资产侧：货币资金 1200（收款入账）+ 存货 800；权益侧：实收资本 1000；
     * 损益：收入 6001 贷 1000（net=-1000）、成本 6401 借 800（net=800）。
     * 未结转损益 = −(−1000) − 800 = 200。未分配利润 = 0(4103/4104) + 200 = 200。
     * 资产 2000 = 权益 (1000+200=1200) + 负债... 需再加负债 800 才平。改造为：
     * 资产 = 货币资金 1200 + 存货 0；负债 应付 0；如下用更直接的配平。
     */
    @Test
    void 资产负债表_开账期未结转损益折入权益_仍平衡() {
        stubAccounts();
        // 经营一笔：销售收现 1200（借货币资金/贷收入 1000 + 贷应交税费 200）；
        // 同时结转成本：借营业成本 800 / 贷存货 800（存货从 800 降到 0，这里直接给净额）。
        // 期初：实收资本 1000（贷）/ 货币资金 1000（借）+ 存货... 简化为下列已配平的凭证累计：
        Mockito.when(dao.cumulativeBalances(PERIOD)).thenReturn(List.of(
                // 资产侧
                row("1001", "2200", "800"),   // 货币资金 net=1400
                row("1405", "800", "800"),    // 存货 net=0（采购800、销售结转800）
                // 权益
                row("4001", "0", "1000"),     // 实收资本 1000
                // 负债
                row("222101", "0", "200"),    // 应交税费 200
                // 损益（未结转）
                row("6001", "0", "1000"),     // 收入 net=-1000
                row("6401", "800", "0")));    // 成本 net=800
        // 资产 = 1400 + 0 = 1400
        // 负债 = 200
        // 未结转损益 = −(−1000) − 800 = 200；未分配利润 = 200
        // 权益 = 实收资本 1000 + 未分配利润 200 = 1200
        // 1400 == 200 + 1200 ✓

        BalanceSheet bs = service.balanceSheet(PERIOD);

        assertEquals("1400", bs.totalAssets());
        assertEquals("200", bs.totalLiabilities());
        assertEquals("1200", bs.totalEquity());
        assertEquals("200", amountOf(bs.equityLines(), "未分配利润"));
        assertTrue(bs.balanced(), "开账期未结转损益折入权益后应平衡");
    }

    /**
     * 某有余额科目不在任何映射表 → 归入"其他"行（按其 AccountType 折入对应组），不静默丢，
     * 且 balanced 仍可校验为 true。注入一个未映射的资产科目 1888。
     */
    @Test
    void 资产负债表_未映射科目_落其他行不丢且仍平衡() {
        stubAccounts(acct("1888", AccountType.ASSET, BalanceDirection.DEBIT));
        Mockito.when(dao.cumulativeBalances(PERIOD)).thenReturn(List.of(
                row("1888", "500", "0"),     // 未映射资产，net=500 → 资产"其他"
                row("4001", "0", "500")));   // 实收资本 500 配平

        BalanceSheet bs = service.balanceSheet(PERIOD);

        assertEquals("500", amountOf(bs.assetLines(), "其他"), "未映射科目须暴露在资产其他行");
        assertEquals("500", bs.totalAssets());
        assertTrue(bs.balanced(), "其他行计入后仍平衡");
    }

    // ================================================================ 3. 利润表 — 映射与计算

    /**
     * 各行映射 + 营业利润/利润总额/净利润计算；收入贷方向（credit−debit）、费用借方向（debit−credit）。
     * 收入 6001=1000；营业成本 6401=400；税金及附加 6403=10；销售费用 6601=20；管理费用 6602=30；
     * 财务费用 6603=5；投资收益 6111=15；营业外收入 6301=8；营业外支出 6711=3；所得税 6801=50。
     * 营业利润 = 1000−400−10−20−30−5+15 = 550
     * 利润总额 = 550+8−3 = 555
     * 净利润 = 555−50 = 505
     */
    @Test
    void 利润表_各行映射_营业利润利润总额净利润计算正确() {
        stubAccounts();
        List<AccountNetRow> movements = List.of(
                row("6001", "0", "1000"),   // 收入 credit−debit=1000
                row("6401", "400", "0"),    // 营业成本 debit−credit=400
                row("6403", "10", "0"),     // 税金及附加 10
                row("6601", "20", "0"),     // 销售费用 20
                row("6602", "30", "0"),     // 管理费用 30
                row("6603", "5", "0"),      // 财务费用 5
                row("6111", "0", "15"),     // 投资收益 15
                row("6301", "0", "8"),      // 营业外收入 8
                row("6711", "3", "0"),      // 营业外支出 3
                row("6801", "50", "0"));    // 所得税 50
        Mockito.when(dao.profitLossMovements(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(movements);

        IncomeStatement is = service.incomeStatement(PERIOD);

        assertEquals("1000", isLine(is, "一、营业收入").currentPeriod());
        assertEquals("400", isLine(is, "减：营业成本").currentPeriod());
        assertEquals("550", isLine(is, "二、营业利润").currentPeriod());
        assertEquals("555", isLine(is, "三、利润总额").currentPeriod());
        assertEquals("505", isLine(is, "四、净利润").currentPeriod());
        // 冗余净利润字段与"净利润"行一致
        assertEquals("505", is.netProfitCurrent());
    }

    /** 净利润 = 收入 − 成本 − 费用 − 税（仅收入与所得税场景，验证 sign 处理）。 */
    @Test
    void 利润表_净利润等于收入减成本费用税() {
        stubAccounts();
        Mockito.when(dao.profitLossMovements(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of(
                        row("6001", "0", "2000"),  // 收入 2000
                        row("6401", "1200", "0"),  // 成本 1200
                        row("6602", "300", "0"),   // 管理费用 300
                        row("6801", "125", "0"))); // 所得税 125

        IncomeStatement is = service.incomeStatement(PERIOD);

        // 2000 − 1200 − 300 − 125 = 375
        assertEquals("375", is.netProfitCurrent());
    }

    /**
     * 防御（评审 P2）：迁移新增、未被任一利润表行映射的损益科目，经"其他损益（未分类）"兜底进入净利润，
     * 保证净利润恒等于全部 PROFIT_LOSS 自然净额（不因新增损益科目静默漏列、交叉校验断裂）。
     */
    @Test
    void 利润表_未映射损益科目_折入其他损益且计入净利润() {
        // 注入一个不在 12 个映射 code 内的损益科目 6052（收入类，贷方向；V19 仅有 6051）
        stubAccounts(acct("6052", AccountType.PROFIT_LOSS, BalanceDirection.CREDIT));
        Mockito.when(dao.profitLossMovements(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of(
                        row("6001", "0", "1000"),   // 已映射营业收入 1000
                        row("6052", "0", "300")));  // 未映射损益科目 收入 300
        IncomeStatement is = service.incomeStatement(PERIOD);
        // 6052 收入贡献 +300 进入"其他损益（未分类）"行（暴露不丢）
        assertEquals("300", isLine(is, "加：其他损益（未分类）").currentPeriod());
        // 净利润 = 1000 + 300 = 1300（含未映射科目，不静默漏列）
        assertEquals("1300", is.netProfitCurrent());
    }

    /** 本期 vs 本年累计调 DAO 区间不同：[P,P] 与 [yyyy01,P]。 */
    @Test
    void 利润表_本期与本年累计_DAO区间入参不同() {
        stubAccounts();
        Mockito.when(dao.profitLossMovements(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of());

        service.incomeStatement(PERIOD);

        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        Mockito.verify(dao, Mockito.times(2)).profitLossMovements(from.capture(), to.capture());
        // 第一次本期 [P,P]，第二次本年累计 [yyyy01,P]
        assertEquals(List.of(PERIOD, YEAR_START), from.getAllValues());
        assertEquals(List.of(PERIOD, PERIOD), to.getAllValues());
    }

    /**
     * 本年累计金额可与本期不同：mock 按区间返回不同发生额，断言两列分别归集。
     * 本期收入 1000；本年累计收入 6000。
     */
    @Test
    void 利润表_本期与本年累计两列独立归集() {
        stubAccounts();
        Mockito.when(dao.profitLossMovements(PERIOD, PERIOD))
                .thenReturn(List.of(row("6001", "0", "1000")));
        Mockito.when(dao.profitLossMovements(YEAR_START, PERIOD))
                .thenReturn(List.of(row("6001", "0", "6000")));

        IncomeStatement is = service.incomeStatement(PERIOD);

        IncomeStatementLine revenue = isLine(is, "一、营业收入");
        assertEquals("1000", revenue.currentPeriod());
        assertEquals("6000", revenue.yearToDate());
        assertEquals("1000", is.netProfitCurrent());
        assertEquals("6000", is.netProfitYtd());
    }

    // ================================================================ 4. 边界

    /** 空账期（无任何凭证行）：报表正常产出、各总计为 0、balanced=true。 */
    @Test
    void 资产负债表_空账期_各总计为零且平衡() {
        stubAccounts();
        Mockito.when(dao.cumulativeBalances(PERIOD)).thenReturn(List.of());

        BalanceSheet bs = service.balanceSheet(PERIOD);

        assertNotNull(bs.assetLines());
        assertEquals("0", bs.totalAssets());
        assertEquals("0", bs.totalLiabilities());
        assertEquals("0", bs.totalEquity());
        assertTrue(bs.balanced());
    }

    /** 利润表空账期：净利润 0，各行存在。 */
    @Test
    void 利润表_空账期_净利润为零() {
        stubAccounts();
        Mockito.when(dao.profitLossMovements(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of());

        IncomeStatement is = service.incomeStatement(PERIOD);

        assertEquals("0", is.netProfitCurrent());
        assertEquals("0", is.netProfitYtd());
        assertFalse(is.lines().isEmpty());
    }

    /**
     * 关账前后利润表数值不变（最易错点 §1.2）：利润表 DAO 已排除 PERIOD_CLOSING，
     * 故无论是否结转，profitLossMovements 返回相同发生额→利润表数值相同。
     * 这里用同一 mock 返回值模拟"关账前后两次查询"，断言净利润一致。
     */
    @Test
    void 利润表_排除结转_关账前后净利润一致() {
        stubAccounts();
        Map<String, AccountNetRow> fixed = Map.of("6001", row("6001", "0", "1000"),
                "6401", row("6401", "600", "0"));
        Mockito.when(dao.profitLossMovements(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.copyOf(fixed.values()));

        String before = service.incomeStatement(PERIOD).netProfitCurrent();
        String after = service.incomeStatement(PERIOD).netProfitCurrent();

        assertEquals(before, after);
        assertEquals("400", before); // 1000 − 600
    }
}
