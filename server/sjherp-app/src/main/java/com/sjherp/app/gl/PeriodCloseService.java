package com.sjherp.app.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckService;
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
import com.sjherp.domain.gl.PeriodStatus;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.domain.inventory.CostingStrategy;

/**
 * 月末结转与关账编排器（M4-T05，路线图 §6，全系统最高风险路径之一）。
 *
 * <p>在总账基建（M4-T01/T02/T03/T04）之上编排「结转前一致性闸门 → 损益结转凭证 → 试算平衡断言
 * → 关账」四步（路线图四步：存货成本结转检查 → 损益结转 → 试算平衡 → 关账）。
 * 全程单一 {@code @Transactional} 原子，任一步失败整事务回滚——绝不留半结转/半关账。
 *
 * <h2>损益结转算法（账结法月结，拆解 §1，BigDecimal 全程 2 位 HALF_UP）</h2>
 * 遍历 {@code trialBalance(period)}，仅 {@link AccountType#PROFIT_LOSS} 科目参与：
 * <ul>
 *   <li>净额 {@code net = Σ借 − Σ贷}；{@code net==0} 跳过（本期无发生额）；</li>
 *   <li>{@code net>0}（费用类借方净额）→ 贷该科目 net 冲平，累加 expenseSum；</li>
 *   <li>{@code net<0}（收入类贷方净额）→ 借该科目 −net 冲平，累加 revenueSum；</li>
 *   <li>4103 本年利润两腿：revenueSum&gt;0 贷一行（收入转入）、expenseSum&gt;0 借一行（费用转入），
 *       各 &gt;0 才出行（避免 0 额行违反 VoucherLine「恰一方&gt;0」）。</li>
 * </ul>
 * 自证平衡：Σ借 = revenueSum + expenseSum = Σ贷（恒等）。净利润 = revenueSum − expenseSum。
 * 损益全无发生额则不生成凭证（无金额无凭证，同 AutoVoucherService signum≤0 语义）。
 *
 * <p>装配：{@code @Service} 组件扫描（同 {@code VoucherAppService}），依赖 GlInfraConfig 注册的
 * 三个总账领域服务 + {@code ConsistencyCheckService}（@Service）+ {@code DocumentNumberGenerator}。
 */
@Service
public class PeriodCloseService {

    /** 4103 本年利润（EQUITY/CREDIT）：损益结转目标科目（拆解 §1，唯一硬编码常量） */
    private static final String ACC_RETAINED_PROFIT = "4103";

    /** 结转凭证号规则：VCH-yyyyMM-序号（与 {@code VoucherAppService.VOUCHER_RULE} 同款） */
    private static final DocumentNumberRule VOUCHER_RULE = DocumentNumberRule.of("VCH");

    /**
     * 结转凭证摘要前缀（拆解 §1）：与 {@link VoucherSourceType#PERIOD_CLOSING} 的 label 解耦——
     * 摘要文案独立于来源类型字典，避免日后调整枚举 label（如改为"期末结转损益"）时拼出"损益"重复。
     */
    private static final String CLOSING_SUMMARY_PREFIX = "期末结转损益 ";

    /** 账期键 yyyyMM 解析格式（→ YearMonth，推算结转凭证日期/编号年月段） */
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final VoucherService voucherService;
    private final AccountService accountService;
    private final AccountingPeriodService accountingPeriodService;
    private final ConsistencyCheckService consistencyCheckService;
    private final DocumentNumberGenerator numberGenerator;

    public PeriodCloseService(VoucherService voucherService, AccountService accountService,
                              AccountingPeriodService accountingPeriodService,
                              ConsistencyCheckService consistencyCheckService,
                              DocumentNumberGenerator numberGenerator) {
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService 不能为空");
        this.accountService = Objects.requireNonNull(accountService, "accountService 不能为空");
        this.accountingPeriodService = Objects.requireNonNull(accountingPeriodService,
                "accountingPeriodService 不能为空");
        this.consistencyCheckService = Objects.requireNonNull(consistencyCheckService,
                "consistencyCheckService 不能为空");
        this.numberGenerator = Objects.requireNonNull(numberGenerator, "numberGenerator 不能为空");
    }

    // ===============================================================
    // 关账可行性预检（只读，向导/Agent 引导展示用，不写库、不抛业务异常）
    // ===============================================================

    /**
     * 关账可行性预检（拆解 §2.1）：算结转预览（不过账）+ 一致性 ERROR/WARN 摘要分列 + 试算平衡
     * Σ借/Σ贷 + 账期态 + 是否已存在结转凭证。{@code closeable = isOpen && 无既存结转凭证 && 无 ERROR}。
     *
     * <p>只读不写、不抛业务异常（账期不存在仍抛 {@code AccountingPeriodNotFoundException} → 404，
     * 属于"查无此账期"而非"不能关账"）。
     *
     * @param period 账期键 yyyyMM
     */
    @Transactional(readOnly = true)
    public PeriodCloseReadiness precheck(String period) {
        String normalizedPeriod = normalize(period);
        AccountingPeriod accountingPeriod = accountingPeriodService.get(normalizedPeriod);
        boolean isOpen = accountingPeriod.getStatus() == PeriodStatus.OPEN;
        boolean alreadyClosed = !voucherService.findBySourceDocNo(normalizedPeriod).isEmpty();

        // 一致性闸门摘要：ERROR/WARN 分列（与 close 同口径，全库勾稽）
        ConsistencyReport report = consistencyCheckService.check();
        List<String> consistencyErrors = summarize(report, ConsistencySeverity.ERROR);
        List<String> consistencyWarnings = summarize(report, ConsistencySeverity.WARN);

        // 损益结转预览（不过账）
        ClosingPlan plan = buildClosingPlan(normalizedPeriod);

        // 试算平衡 Σ借/Σ贷（当前已过账凭证派生）
        List<AccountBalance> balances = voucherService.trialBalance(normalizedPeriod);
        BigDecimal trialDebit = sumDebit(balances);
        BigDecimal trialCredit = sumCredit(balances);

        boolean closeable = isOpen && !alreadyClosed && consistencyErrors.isEmpty();

        return new PeriodCloseReadiness(normalizedPeriod, accountingPeriod.getStatus().name(), closeable,
                alreadyClosed, consistencyErrors, consistencyWarnings, plan.previewLines(),
                plain(plan.revenueSum()), plain(plan.expenseSum()), plain(plan.netProfit()),
                plain(trialDebit), plain(trialCredit));
    }

    // ===============================================================
    // 执行月末结转关账（验收核心，单一 @Transactional 原子，拆解 §2.1 七步）
    // ===============================================================

    /**
     * 执行月末结转关账（拆解 §2.1 七步，任一失败整事务回滚）：
     * <ol>
     *   <li>账期 OPEN 校验（不存在→404；非 OPEN → {@link PeriodCloseBlockedException}）；</li>
     *   <li>重结防护（已存在结转凭证 → blocked，重开后重结须先冲销，留 M4-T07）；</li>
     *   <li>结转前一致性闸门（ERROR break 非空 → blocked 携 reasons；WARN 不阻塞）；</li>
     *   <li>损益结转（有发生额则建结转凭证 + 过账，无则跳过 closingVoucherDocNo=null）；</li>
     *   <li>试算平衡断言（Σ借==Σ贷 且所有 PROFIT_LOSS 净额=0，不成立 → IllegalStateException 回滚）；</li>
     *   <li>关账（OPEN→CLOSED）；</li>
     *   <li>返回 {@link PeriodCloseResult}。</li>
     * </ol>
     *
     * @param period   账期键 yyyyMM
     * @param operator 操作人
     */
    @Transactional
    public PeriodCloseResult close(String period, String operator) {
        requireOperator(operator);
        String normalizedPeriod = normalize(period);

        // ① 账期 OPEN 校验（不存在→AccountingPeriodNotFoundException→404）
        AccountingPeriod accountingPeriod = accountingPeriodService.get(normalizedPeriod);
        if (accountingPeriod.getStatus() != PeriodStatus.OPEN) {
            throw new PeriodCloseBlockedException(
                    "账期[" + normalizedPeriod + "] 当前状态为 " + accountingPeriod.getStatus().label()
                            + "，仅 OPEN 账期可关账（已关账不可重复关）");
        }

        // ② 重结防护（幂等/安全）：close 原子，正常路径 OPEN 时不应有结转凭证；命中即重开后重结场景，硬拒
        List<Voucher> existingClosings = voucherService.findBySourceDocNo(normalizedPeriod);
        if (!existingClosings.isEmpty()) {
            String existingDocNo = existingClosings.get(0).getDocNo();
            throw new PeriodCloseBlockedException("账期[" + normalizedPeriod + "] 已存在结转损益凭证 "
                    + existingDocNo + "；重开后重新关账须先冲销原结转凭证（统一在 M4-T07）");
        }

        // ③ 结转前一致性闸门（"存货成本结转检查"=结转前账实/勾稽校验）：有 ERROR 即拒，WARN 不阻塞
        ConsistencyReport report = consistencyCheckService.check();
        List<String> errorReasons = summarize(report, ConsistencySeverity.ERROR);
        if (!errorReasons.isEmpty()) {
            throw new PeriodCloseBlockedException(
                    "账期[" + normalizedPeriod + "] 存在 " + errorReasons.size()
                            + " 项数据一致性错误（账实/勾稽不平），治理后方可关账",
                    errorReasons);
        }

        // ④ 损益结转：按 §1 算法构造结转凭证；有发生额则建+过账（账期仍 OPEN，过账通过），无则跳过
        ClosingPlan plan = buildClosingPlan(normalizedPeriod);
        String closingVoucherDocNo = null;
        if (!plan.lines().isEmpty()) {
            YearMonth yearMonth = YearMonth.parse(normalizedPeriod, PERIOD_FORMAT);
            LocalDate voucherDate = yearMonth.atEndOfMonth();
            String docNo = numberGenerator.generate(VOUCHER_RULE, yearMonth);
            String summary = CLOSING_SUMMARY_PREFIX + normalizedPeriod;
            voucherService.createFromSource(docNo, normalizedPeriod, voucherDate, summary,
                    VoucherSourceType.PERIOD_CLOSING, normalizedPeriod, plan.lines(), operator);
            voucherService.post(docNo, operator);
            closingVoucherDocNo = docNo;
        }

        // ⑤ 试算平衡断言（结转完整性兜底，恒成立；不成立=结转逻辑缺陷，回滚）
        List<AccountBalance> postBalances = voucherService.trialBalance(normalizedPeriod);
        BigDecimal trialDebit = sumDebit(postBalances);
        BigDecimal trialCredit = sumCredit(postBalances);
        if (trialDebit.compareTo(trialCredit) != 0) {
            throw new IllegalStateException("账期[" + normalizedPeriod + "] 结转后试算不平：Σ借 "
                    + trialDebit.toPlainString() + " ≠ Σ贷 " + trialCredit.toPlainString());
        }
        for (AccountBalance balance : postBalances) {
            if (isProfitLoss(balance.accountCode()) && balance.netBalance().signum() != 0) {
                throw new IllegalStateException("账期[" + normalizedPeriod + "] 损益科目["
                        + balance.accountCode() + "] 结转后净额非零："
                        + balance.netBalance().toPlainString());
            }
        }

        // ⑥ 关账（OPEN→CLOSED，记关账人/时间，@Audited）
        AccountingPeriod closed = accountingPeriodService.close(normalizedPeriod, operator);

        // ⑦ 返回结果
        return new PeriodCloseResult(normalizedPeriod, closingVoucherDocNo,
                plain(plan.revenueSum()), plain(plan.expenseSum()), plain(plan.netProfit()),
                plain(trialDebit), plain(trialCredit), closed.getClosedBy(),
                closed.getClosedAt() == null ? null : closed.getClosedAt().toString());
    }

    // ===============================================================
    // 损益结转算法（拆解 §1，纯派生：从 trialBalance 构造结转分录，不写库）
    // ===============================================================

    /**
     * 按账期试算平衡构造损益结转分录与预览（拆解 §1）。仅 {@link AccountType#PROFIT_LOSS} 参与；
     * 调用方负责落库（close）或仅展示（precheck）。本方法只读不写、不抛业务异常。
     */
    private ClosingPlan buildClosingPlan(String period) {
        List<AccountBalance> balances = voucherService.trialBalance(period);
        List<VoucherLineInput> lines = new ArrayList<>();
        List<ClosingPreviewLine> previewLines = new ArrayList<>();
        BigDecimal revenueSum = BigDecimal.ZERO;
        BigDecimal expenseSum = BigDecimal.ZERO;

        for (AccountBalance balance : balances) {
            Account account = accountService.get(balance.accountCode());
            if (account.getType() != AccountType.PROFIT_LOSS) {
                continue; // 仅损益类参与结转（COST 期末转库存/WIP 留 M5；资产/负债/权益不结转）
            }
            BigDecimal net = balance.netBalance(); // = Σ借 − Σ贷
            int sign = net.compareTo(BigDecimal.ZERO);
            if (sign == 0) {
                continue; // 本期无发生额，跳过
            }
            if (sign > 0) {
                // 费用类（借方净额）→ 贷该科目 net 冲平
                lines.add(creditLine(account.getCode(), net));
                previewLines.add(previewLine(account, BigDecimal.ZERO, net));
                expenseSum = expenseSum.add(net);
            } else {
                // 收入类（贷方净额）→ 借该科目 −net 冲平
                BigDecimal amount = net.negate();
                lines.add(debitLine(account.getCode(), amount));
                previewLines.add(previewLine(account, amount, BigDecimal.ZERO));
                revenueSum = revenueSum.add(amount);
            }
        }

        // 4103 本年利润两腿（各 >0 才出行）：收入转入贷增、费用转入借减。
        // 仅在确需出 4103 行时才读取科目——损益全无发生额（两 sum 均 0）则不读、不建凭证，
        // 避免对不会生成的行做一次无用查询（P3 健壮性，预置科目守护下原行为本就正确）。
        if (revenueSum.signum() > 0 || expenseSum.signum() > 0) {
            Account retainedProfit = accountService.get(ACC_RETAINED_PROFIT);
            if (revenueSum.signum() > 0) {
                lines.add(creditLine(ACC_RETAINED_PROFIT, revenueSum));
                previewLines.add(previewLine(retainedProfit, BigDecimal.ZERO, revenueSum));
            }
            if (expenseSum.signum() > 0) {
                lines.add(debitLine(ACC_RETAINED_PROFIT, expenseSum));
                previewLines.add(previewLine(retainedProfit, expenseSum, BigDecimal.ZERO));
            }
        }

        BigDecimal netProfit = revenueSum.subtract(expenseSum).setScale(SCALE, ROUNDING);
        return new ClosingPlan(lines, previewLines, scale(revenueSum), scale(expenseSum), netProfit);
    }

    /** 结转计划（落库行 + 预览行 + 汇总；revenueSum/expenseSum/netProfit 均 2 位）。 */
    private record ClosingPlan(List<VoucherLineInput> lines, List<ClosingPreviewLine> previewLines,
                               BigDecimal revenueSum, BigDecimal expenseSum, BigDecimal netProfit) {
    }

    // ===============================================================
    // 辅助
    // ===============================================================

    private static final int SCALE = CostingStrategy.AMOUNT_SCALE;
    private static final java.math.RoundingMode ROUNDING = CostingStrategy.ROUNDING;

    /** 某科目是否损益类（结转完整性断言用） */
    private boolean isProfitLoss(String accountCode) {
        return accountService.get(accountCode).getType() == AccountType.PROFIT_LOSS;
    }

    /** 把一致性报告按严重度筛出 break 的人读摘要清单（与 ConsistencyBreak.message 同源） */
    private static List<String> summarize(ConsistencyReport report, ConsistencySeverity severity) {
        List<String> summaries = new ArrayList<>();
        for (ConsistencyBreak brk : report.breaks()) {
            if (brk.severity() == severity) {
                summaries.add(formatBreak(brk));
            }
        }
        return summaries;
    }

    /** 单条 break 摘要：规则 + 对象键 + 期望/实际 + 说明（供向导/Agent 复述为何不能关账） */
    private static String formatBreak(ConsistencyBreak brk) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(brk.checkType().name()).append(']');
        if (brk.key() != null) {
            sb.append(' ').append(brk.key());
        }
        if (brk.message() != null) {
            sb.append(' ').append(brk.message());
        }
        sb.append("（期望=").append(brk.expected()).append(", 实际=").append(brk.actual()).append('）');
        return sb.toString();
    }

    private static BigDecimal sumDebit(List<AccountBalance> balances) {
        return balances.stream().map(AccountBalance::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, ROUNDING);
    }

    private static BigDecimal sumCredit(List<AccountBalance> balances) {
        return balances.stream().map(AccountBalance::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, ROUNDING);
    }

    private static ClosingPreviewLine previewLine(Account account, BigDecimal debit,
                                                  BigDecimal credit) {
        return new ClosingPreviewLine(account.getCode(), account.getName(),
                scale(debit).toPlainString(), scale(credit).toPlainString());
    }

    private static VoucherLineInput debitLine(String accountCode, BigDecimal amount) {
        return new VoucherLineInput(accountCode, scale(amount), BigDecimal.ZERO, "期末结转损益");
    }

    private static VoucherLineInput creditLine(String accountCode, BigDecimal amount) {
        return new VoucherLineInput(accountCode, BigDecimal.ZERO, scale(amount), "期末结转损益");
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, ROUNDING);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String normalize(String period) {
        return Objects.requireNonNull(period, "账期不能为空").strip();
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
