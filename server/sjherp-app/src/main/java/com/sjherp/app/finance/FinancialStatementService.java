package com.sjherp.app.finance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.sjherp.app.finance.FinancialStatementDao.AccountNetRow;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheetLine;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatementLine;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountType;

/**
 * 财务报表应用服务（M4-T06）：由凭证累计派生<b>资产负债表</b>（时点）与<b>利润表</b>（期间 + 本年累计）。
 *
 * <p>取数全经 {@link FinancialStatementDao} 只读 SQL（零写库）；科目类别/余额方向/名称取自
 * {@link AccountService#listAll()}。报表行映射、平衡校验、净利润计算（业务/会计逻辑）集中在本类，
 * DAO 与会计准则解耦。金额一律 {@link BigDecimal}（DECIMAL，2 位），DTO 落 {@code toPlainString}。
 *
 * <h2>关键口径（设计真源 §2）</h2>
 * <ul>
 *   <li>资产负债表恒平衡靠"<b>本期未结转损益</b>折入未分配利润"（开账期/未关账期同样平衡）；</li>
 *   <li>利润表已在 DAO 排除 PERIOD_CLOSING 结转凭证（否则损益自相抵消归零）；</li>
 *   <li>不可分类的有余额 leaf code 归入"其他"行，绝不静默丢（财务报表红线）。</li>
 * </ul>
 */
@Service
public class FinancialStatementService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** 报表归集"其他"兜底行名（资产/负债/权益三组各自的未映射科目落此行，暴露不丢）。 */
    private static final String OTHER_LINE_NAME = "其他";

    private final FinancialStatementDao dao;
    private final AccountService accountService;

    public FinancialStatementService(FinancialStatementDao dao, AccountService accountService) {
        this.dao = Objects.requireNonNull(dao, "dao 不能为空");
        this.accountService = Objects.requireNonNull(accountService, "accountService 不能为空");
    }

    // =====================================================================
    // 资产负债表（时点，Assets = Liabilities + Equity）
    // =====================================================================

    /**
     * 资产负债表：截至账期 P 末（时点）。
     *
     * <p>口径（§2.1）：货币资金合并 1001/1002/1012；存货含 contra 1471 减项；固定资产减累计折旧 1602；
     * 应付账款=220201+220202；应交税费=222101+222102；未分配利润=4103+4104+<b>本期未结转损益</b>
     * （=−Σ(PROFIT_LOSS net)−Σ(COST net)，保证开账期也平衡）。每张资产行按借方向净额（contra 自然为减），
     * 负债/权益行按贷方向净额。不可分类有余额 leaf 科目归入"其他"行不丢，并据其类别折入对应组。
     *
     * @param period 账期键 yyyyMM（6 位，controller 已校验格式）
     */
    public BalanceSheet balanceSheet(String period) {
        Objects.requireNonNull(period, "period 不能为空");

        // 科目元数据（code → Account）+ 累计借方向净额（code → debit−credit）
        Map<String, Account> accounts = accountsByCode();
        Map<String, BigDecimal> debitNet = new HashMap<>();
        for (AccountNetRow row : dao.cumulativeBalances(period)) {
            debitNet.merge(row.accountCode(), row.totalDebit().subtract(row.totalCredit()), BigDecimal::add);
        }

        // 已被报表行显式映射的 code（用于"其他"行兜底剩余有余额科目，暴露不丢）
        Set<String> consumed = new HashSet<>();

        // ---- 资产：借方向净额（contra 科目天然为减项，1471/1602 余额方向 CREDIT，其 net 为负，加总即减）
        List<BalanceSheetLine> assetLines = new ArrayList<>();
        assetLines.add(line("货币资金", sumDebitNet(debitNet, consumed, "1001", "1002", "1012")));
        assetLines.add(line("短期投资", sumDebitNet(debitNet, consumed, "1101")));
        assetLines.add(line("应收账款", sumDebitNet(debitNet, consumed, "1122")));
        assetLines.add(line("预付账款", sumDebitNet(debitNet, consumed, "1123")));
        assetLines.add(line("其他应收款", sumDebitNet(debitNet, consumed, "1221")));
        assetLines.add(line("存货", sumDebitNet(debitNet, consumed, "1401", "1403", "1405", "1407", "1411", "1471")));
        assetLines.add(line("固定资产", sumDebitNet(debitNet, consumed, "1601", "1602")));
        assetLines.add(line("无形资产", sumDebitNet(debitNet, consumed, "1701")));
        assetLines.add(line("长期待摊费用", sumDebitNet(debitNet, consumed, "1801")));

        // ---- 负债：贷方向净额 = −(借方向净额)
        List<BalanceSheetLine> liabilityLines = new ArrayList<>();
        liabilityLines.add(line("短期借款", creditNet(debitNet, consumed, "2001")));
        liabilityLines.add(line("应付账款", creditNet(debitNet, consumed, "220201", "220202")));
        liabilityLines.add(line("预收账款", creditNet(debitNet, consumed, "2203")));
        liabilityLines.add(line("应付职工薪酬", creditNet(debitNet, consumed, "2211")));
        liabilityLines.add(line("应交税费", creditNet(debitNet, consumed, "222101", "222102")));
        liabilityLines.add(line("其他应付款", creditNet(debitNet, consumed, "2241")));
        liabilityLines.add(line("长期借款", creditNet(debitNet, consumed, "2501")));

        // ---- 所有者权益：贷方向净额；未分配利润 = 4103 + 4104 + 本期未结转损益
        BigDecimal unallocated = creditNet(debitNet, consumed, "4103", "4104")
                .add(unsettledProfit(accounts, debitNet, consumed));
        List<BalanceSheetLine> equityLines = new ArrayList<>();
        equityLines.add(line("实收资本", creditNet(debitNet, consumed, "4001")));
        equityLines.add(line("资本公积", creditNet(debitNet, consumed, "4002")));
        equityLines.add(line("盈余公积", creditNet(debitNet, consumed, "4101")));
        equityLines.add(line("未分配利润", unallocated));

        // ---- "其他"兜底：所有有余额但未被任何报表行消费的 leaf code，按类别折入资产/负债/权益（不静默丢）
        BigDecimal otherAsset = ZERO;
        BigDecimal otherLiability = ZERO;
        BigDecimal otherEquity = ZERO;
        for (Map.Entry<String, BigDecimal> e : debitNet.entrySet()) {
            if (consumed.contains(e.getKey()) || e.getValue().signum() == 0) {
                continue;
            }
            // COST/PROFIT_LOSS 已被 unsettledProfit 标记 consumed（计入未分配利润），不会走到这里。
            // 余下为未映射的 ASSET/LIABILITY/EQUITY，或未建档（acc==null）的异常科目——一律暴露不丢。
            Account acc = accounts.get(e.getKey());
            // 未建档/类别异常的科目暴露为资产侧"其他"（借方向原值），保证 balanced 仍可校验
            AccountType type = acc == null ? AccountType.ASSET : acc.getType();
            switch (type) {
                case ASSET -> otherAsset = otherAsset.add(e.getValue());
                case LIABILITY -> otherLiability = otherLiability.add(e.getValue().negate());
                case EQUITY -> otherEquity = otherEquity.add(e.getValue().negate());
                default -> otherAsset = otherAsset.add(e.getValue());
            }
        }
        if (otherAsset.signum() != 0) {
            assetLines.add(line(OTHER_LINE_NAME, otherAsset));
        }
        if (otherLiability.signum() != 0) {
            liabilityLines.add(line(OTHER_LINE_NAME, otherLiability));
        }
        if (otherEquity.signum() != 0) {
            equityLines.add(line(OTHER_LINE_NAME, otherEquity));
        }

        BigDecimal totalAssets = total(assetLines);
        BigDecimal totalLiabilities = total(liabilityLines);
        BigDecimal totalEquity = total(equityLines);
        boolean balanced = totalAssets.compareTo(totalLiabilities.add(totalEquity)) == 0;

        return new BalanceSheet(period,
                assetLines, plain(totalAssets),
                liabilityLines, plain(totalLiabilities),
                equityLines, plain(totalEquity),
                balanced);
    }

    // =====================================================================
    // 利润表（期间 + 本年累计；Revenue − Expense = Net Profit）
    // =====================================================================

    /**
     * 利润表：本期 = [P,P]，本年累计 = [当年 1 月 yyyy01, P]（设计真源 §2.2，小企业会计准则行次）。
     *
     * <p>每行金额=该科目自然方向净额（收入类 credit−debit、费用/成本类 debit−credit），取自 DAO
     * 排除 PERIOD_CLOSING 后的发生额。净利润 = 利润总额 − 所得税；交叉校验：净利润 == 同期结转 netProfit。
     *
     * @param period 账期键 yyyyMM（6 位，controller 已校验格式）
     */
    public IncomeStatement incomeStatement(String period) {
        Objects.requireNonNull(period, "period 不能为空");

        // 本期 [P,P]
        Map<String, BigDecimal> cur = movementByCode(dao.profitLossMovements(period, period));
        // 本年累计 [yyyy01, P]
        String yearStart = period.substring(0, 4) + "01";
        Map<String, BigDecimal> ytd = movementByCode(dao.profitLossMovements(yearStart, period));

        // 已被利润表行显式映射的损益 code（用于"其他损益"兜底未映射损益科目，暴露不丢——
        // 与资产负债表"其他"行同源原则；保证 净利润 == Σ全部损益类自然净额 == 结转 netProfit）
        Set<String> consumed = new HashSet<>();

        List<IncomeStatementLine> lines = new ArrayList<>();

        // ① 营业收入（收入类，贷方向） = 6001 + 6051
        TwoCol revenue = expense(cur, ytd, false, consumed, "6001", "6051");
        lines.add(twoColLine("一、营业收入", revenue));
        // ② 减：营业成本（费用/成本类，借方向） = 6401 + 6402
        TwoCol opCost = expense(cur, ytd, true, consumed, "6401", "6402");
        lines.add(twoColLine("减：营业成本", opCost));
        // ③ 减：税金及附加 = 6403
        TwoCol taxSurcharge = expense(cur, ytd, true, consumed, "6403");
        lines.add(twoColLine("减：税金及附加", taxSurcharge));
        // ④ 减：销售费用 = 6601
        TwoCol sellingExp = expense(cur, ytd, true, consumed, "6601");
        lines.add(twoColLine("减：销售费用", sellingExp));
        // ⑤ 减：管理费用 = 6602
        TwoCol adminExp = expense(cur, ytd, true, consumed, "6602");
        lines.add(twoColLine("减：管理费用", adminExp));
        // ⑥ 减：财务费用 = 6603
        TwoCol financeExp = expense(cur, ytd, true, consumed, "6603");
        lines.add(twoColLine("减：财务费用", financeExp));
        // ⑦ 加：投资收益（收入类，贷方向） = 6111
        TwoCol investIncome = expense(cur, ytd, false, consumed, "6111");
        lines.add(twoColLine("加：投资收益", investIncome));

        // ⑧ 营业利润 = ① − ② − ③ − ④ − ⑤ − ⑥ + ⑦
        TwoCol opProfit = revenue.minus(opCost).minus(taxSurcharge).minus(sellingExp)
                .minus(adminExp).minus(financeExp).plus(investIncome);
        lines.add(twoColLine("二、营业利润", opProfit));

        // ⑨ 加：营业外收入 = 6301
        TwoCol nonOpIncome = expense(cur, ytd, false, consumed, "6301");
        lines.add(twoColLine("加：营业外收入", nonOpIncome));
        // ⑩ 减：营业外支出 = 6711
        TwoCol nonOpExp = expense(cur, ytd, true, consumed, "6711");
        lines.add(twoColLine("减：营业外支出", nonOpExp));

        // ⑪ 利润总额 = ⑧ + ⑨ − ⑩
        TwoCol totalProfit = opProfit.plus(nonOpIncome).minus(nonOpExp);
        lines.add(twoColLine("三、利润总额", totalProfit));

        // ⑫ 减：所得税费用 = 6801
        TwoCol incomeTax = expense(cur, ytd, true, consumed, "6801");
        lines.add(twoColLine("减：所得税费用", incomeTax));

        // 防御（评审 P2）：未被上述行映射的损益类科目（如迁移新增损益科目而漏列）折入"其他损益"，
        // 使利润表净利润恒等于全部 PROFIT_LOSS 科目自然净额（= 结转 netProfit = 资产负债表 unsettledProfit
        // 的损益部分），避免新增科目时静默漏列、交叉校验断裂。利润贡献 = −借方向净额（收入加、费用减）。
        TwoCol otherProfitLoss = unmappedProfitLoss(cur, ytd, consumed);
        if (otherProfitLoss.current.signum() != 0 || otherProfitLoss.ytd.signum() != 0) {
            lines.add(twoColLine("加：其他损益（未分类）", otherProfitLoss));
        }

        // ⑬ 净利润 = ⑪ − ⑫ + 其他损益（未分类）
        TwoCol netProfit = totalProfit.minus(incomeTax).plus(otherProfitLoss);
        lines.add(twoColLine("四、净利润", netProfit));

        return new IncomeStatement(period, lines, plain(netProfit.current), plain(netProfit.ytd));
    }

    // =====================================================================
    // 内部辅助
    // =====================================================================

    /** 科目元数据：code → Account（含未启用，报表取数不看启停，历史余额都要反映）。 */
    private Map<String, Account> accountsByCode() {
        Map<String, Account> map = new HashMap<>();
        for (Account a : accountService.listAll()) {
            map.put(a.getCode(), a);
        }
        return map;
    }

    /** 资产侧若干 code 借方向净额之和（消费标记 code）。 */
    private static BigDecimal sumDebitNet(Map<String, BigDecimal> debitNet, Set<String> consumed,
                                          String... codes) {
        BigDecimal sum = ZERO;
        for (String code : codes) {
            consumed.add(code);
            BigDecimal v = debitNet.get(code);
            if (v != null) {
                sum = sum.add(v);
            }
        }
        return sum;
    }

    /** 负债/权益侧若干 code 贷方向净额之和 = −(借方向净额之和)（消费标记 code）。 */
    private static BigDecimal creditNet(Map<String, BigDecimal> debitNet, Set<String> consumed,
                                        String... codes) {
        return sumDebitNet(debitNet, consumed, codes).negate();
    }

    /**
     * 本期未结转损益（动态本年利润）= −Σ(PROFIT_LOSS net) − Σ(COST net)
     * （损益类借方净额为正=费用、贷方为负=收入；该式=收入−费用−成本=尚未结转的当期利润）。
     * 关账后损益/成本类累计净额=0，此项=0，未分配利润=4103+4104。计入的损益/成本 code 标记消费。
     */
    private static BigDecimal unsettledProfit(Map<String, Account> accounts,
                                              Map<String, BigDecimal> debitNet, Set<String> consumed) {
        BigDecimal profit = ZERO;
        for (Map.Entry<String, BigDecimal> e : debitNet.entrySet()) {
            Account acc = accounts.get(e.getKey());
            if (acc == null) {
                continue;
            }
            AccountType type = acc.getType();
            if (type == AccountType.PROFIT_LOSS || type == AccountType.COST) {
                consumed.add(e.getKey());
                profit = profit.subtract(e.getValue());
            }
        }
        return profit;
    }

    /** 区间发生额映射：code → 借方向净额（debit−credit）。 */
    private static Map<String, BigDecimal> movementByCode(List<AccountNetRow> rows) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (AccountNetRow row : rows) {
            map.merge(row.accountCode(), row.totalDebit().subtract(row.totalCredit()), BigDecimal::add);
        }
        return map;
    }

    /**
     * 利润表行金额（本期 + 本年累计）：若干 code 的自然方向净额之和。
     *
     * @param expenseSide true=费用/成本类（借方向，取 debit−credit 即 debitNet）；
     *                    false=收入类（贷方向，取 credit−debit = −debitNet）
     */
    private static TwoCol expense(Map<String, BigDecimal> cur, Map<String, BigDecimal> ytd,
                                  boolean expenseSide, Set<String> consumed, String... codes) {
        BigDecimal curSum = ZERO;
        BigDecimal ytdSum = ZERO;
        for (String code : codes) {
            consumed.add(code); // 标记已映射，供"其他损益"兜底排除
            BigDecimal c = cur.get(code);
            BigDecimal y = ytd.get(code);
            if (c != null) {
                curSum = curSum.add(c);
            }
            if (y != null) {
                ytdSum = ytdSum.add(y);
            }
        }
        // debitNet 为借方向净额；费用类自然方向=借方向（直接用），收入类自然方向=贷方向（取负）
        return expenseSide ? new TwoCol(curSum, ytdSum) : new TwoCol(curSum.negate(), ytdSum.negate());
    }

    /**
     * 未被利润表行映射的损益类（PROFIT_LOSS）科目的利润贡献（本期 + 本年累计）。
     *
     * <p>遍历本期/本年累计有发生额的科目，取其中 {@code type==PROFIT_LOSS} 且未被任一利润表行
     * {@code consumed} 的科目，利润贡献 = −借方向净额（收入加、费用减）。正常情况下 V19 预置 12 个
     * 损益科目已被各行完整覆盖，本项为 0；当迁移新增损益科目而漏列时，其发生额经此兜底进入净利润，
     * 保证「利润表净利润 == 全部损益类自然净额 == 结转 netProfit」不变式不因新增科目静默断裂。
     */
    private TwoCol unmappedProfitLoss(Map<String, BigDecimal> cur, Map<String, BigDecimal> ytd,
                                      Set<String> consumed) {
        Map<String, Account> accounts = accountsByCode();
        Set<String> codes = new HashSet<>(cur.keySet());
        codes.addAll(ytd.keySet());
        BigDecimal curSum = ZERO;
        BigDecimal ytdSum = ZERO;
        for (String code : codes) {
            if (consumed.contains(code)) {
                continue;
            }
            Account acc = accounts.get(code);
            if (acc == null || acc.getType() != AccountType.PROFIT_LOSS) {
                continue;
            }
            BigDecimal c = cur.get(code);
            BigDecimal y = ytd.get(code);
            if (c != null) {
                curSum = curSum.subtract(c); // 利润贡献 = −借方向净额
            }
            if (y != null) {
                ytdSum = ytdSum.subtract(y);
            }
        }
        return new TwoCol(curSum, ytdSum);
    }

    /** 利润表双列净额（本期/本年累计），支持行间加减（营业利润/利润总额/净利润聚合）。 */
    private record TwoCol(BigDecimal current, BigDecimal ytd) {
        TwoCol plus(TwoCol o) {
            return new TwoCol(current.add(o.current), ytd.add(o.ytd));
        }

        TwoCol minus(TwoCol o) {
            return new TwoCol(current.subtract(o.current), ytd.subtract(o.ytd));
        }
    }

    private static IncomeStatementLine twoColLine(String name, TwoCol v) {
        return new IncomeStatementLine(name, plain(v.current), plain(v.ytd));
    }

    private static BalanceSheetLine line(String name, BigDecimal amount) {
        return new BalanceSheetLine(name, plain(amount));
    }

    private static BigDecimal total(List<BalanceSheetLine> lines) {
        BigDecimal sum = ZERO;
        for (BalanceSheetLine l : lines) {
            sum = sum.add(new BigDecimal(l.amount()));
        }
        return sum;
    }

    private static String plain(BigDecimal value) {
        return (value == null ? ZERO : value).toPlainString();
    }
}
