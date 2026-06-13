package com.sjherp.domain.gl;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 凭证领域服务（所有凭证写操作的唯一入口，CLAUDE.md 原则 1/2/3，全系统最高风险的财务核心）。
 *
 * <p>纯 Java 零依赖：依赖凭证仓储端口 {@link VoucherRepository}、科目领域服务 {@link AccountService}
 * （校验行科目存在/末级/启用）、账期领域服务 {@link AccountingPeriodService}（账期存在 / OPEN 校验）
 * 与领域事件发布器 {@link DomainEventPublisher}，由 app 层装配并把状态流转包进同一外层事务。
 *
 * <h2>建单（草稿可建，账期只需存在不要求 OPEN）</h2>
 * 校验账期存在、凭证日期落在账期内、逐行科目存在且末级且启用；构造 {@link Voucher#create}
 * （借贷不平在此抛 {@link VoucherNotBalancedException}，验收①）；save。
 *
 * <h2>过账（关账守卫，验收②）</h2>
 * {@link #post} 先校验所属账期 OPEN，否则抛 {@link PeriodClosedException}（CLAUDE.md 原则 2：
 * 关账后禁止过账）；再 {@code voucher.post(operator)}（DRAFT→APPROVED 一步流转）；save。
 *
 * <h2>冲销（留 T07）</h2>
 * {@link #reverse} 当前抛 {@link UnsupportedOperationException}（照 PurchaseInvoiceService.reverse
 * 先例，红字冲销统一在 M4-T07 落地）。凭证本身不提供物理删除（CLAUDE.md 原则 2）。
 */
public class VoucherService {

    private final VoucherRepository repository;
    private final AccountService accountService;
    private final AccountingPeriodService accountingPeriodService;

    /** 领域事件发布器：状态流转经它自动落 document.status_changed 审计 */
    private final DomainEventPublisher eventPublisher;

    public VoucherService(VoucherRepository repository, AccountService accountService,
                          AccountingPeriodService accountingPeriodService,
                          DomainEventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.accountService = Objects.requireNonNull(accountService, "accountService 不能为空");
        this.accountingPeriodService = Objects.requireNonNull(accountingPeriodService,
                "accountingPeriodService 不能为空");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher 不能为空");
    }

    /**
     * 创建凭证（草稿）：校验账期存在、凭证日期落在账期内、逐行科目末级且启用，构造时强制借贷平衡。
     *
     * @param docNo       单据号（VCH-yyyyMM-序号，app 层用 DocumentNumberGenerator 生成）
     * @param period      所属账期键 yyyyMM
     * @param voucherDate 凭证日期（须落在 period 内）
     * @param summary     凭证摘要（可空）
     * @param lines       行输入（≥2 行，经 Voucher.create 强制借贷平衡）
     * @param operator    操作人
     */
    @Audited(action = "voucher.create", targetType = "voucher")
    public Voucher create(String docNo, String period, LocalDate voucherDate, String summary,
                          List<VoucherLineInput> lines, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(docNo, "单据号不能为空");
        Objects.requireNonNull(period, "账期不能为空");
        Objects.requireNonNull(voucherDate, "凭证日期不能为空");
        Objects.requireNonNull(lines, "凭证行不能为空");

        String normalizedPeriod = period.strip();
        // 账期存在（草稿可建，不要求 OPEN——过账时再校验 OPEN，见 post）
        accountingPeriodService.get(normalizedPeriod);
        // 凭证日期落在账期内
        String datePeriod = YearMonth.from(voucherDate).format(DateTimeFormatter.ofPattern("yyyyMM"));
        if (!datePeriod.equals(normalizedPeriod)) {
            throw new IllegalArgumentException("凭证日期 " + voucherDate + " 不在账期[" + normalizedPeriod
                    + "] 内（凭证日期所属账期为 " + datePeriod + "）");
        }

        // 逐行：科目存在 + 末级 + 启用；行号按输入顺序从 1 起编排
        List<VoucherLine> domainLines = new ArrayList<>(lines.size());
        int lineNo = 1;
        for (VoucherLineInput input : lines) {
            Objects.requireNonNull(input, "凭证行输入不能为空");
            Account account = accountService.get(input.accountCode());
            if (!account.isLeaf()) {
                throw new IllegalArgumentException("科目[" + account.getCode()
                        + "] 非末级科目，不可挂凭证行");
            }
            if (!account.isEnabled()) {
                throw new IllegalArgumentException("科目[" + account.getCode() + "] 已停用，不可挂凭证行");
            }
            domainLines.add(VoucherLine.create(lineNo++, account.getCode(), input.debit(),
                    input.credit(), input.summary()));
        }

        // 构造时强制借贷平衡（验收①：不平抛 VoucherNotBalancedException，到不了 save）
        Voucher voucher = Voucher.create(docNo, normalizedPeriod, voucherDate, null, summary,
                null, null, operator, domainLines);
        voucher.registerEventPublisher(eventPublisher);
        repository.save(voucher);
        return voucher;
    }

    /**
     * 过账凭证：DRAFT → APPROVED。先校验账期 OPEN，否则抛 {@link PeriodClosedException}（验收②）。
     *
     * <p>调用方（app 装配的本服务）须以外层事务包住状态流转。T02 将在此追加自动凭证联动扩展点。
     */
    @Audited(action = "voucher.post", targetType = "voucher")
    public Voucher post(String docNo, String operator) {
        requireOperator(operator);
        Voucher voucher = get(docNo);
        // 关账守卫（验收②）：账期非 OPEN 一律拒绝过账，事务回滚
        if (!accountingPeriodService.isOpen(voucher.getPeriod())) {
            throw new PeriodClosedException(voucher.getPeriod());
        }
        voucher.registerEventPublisher(eventPublisher);
        voucher.post(operator);
        repository.save(voucher);
        return voucher;
    }

    /**
     * 冲销已过账凭证（红字凭证）。
     *
     * <p>TODO（M4-T07 统一做）：生成反向凭证（红字分录抵消原凭证），原单 APPROVED → REVERSED
     * 并红字关联。当前未实现——凭证本身不提供物理删除（CLAUDE.md 原则 2：财务记录只可冲销不可删除）。
     */
    @Audited(action = "voucher.reverse", targetType = "voucher")
    public Voucher reverse(String docNo, String operator) {
        requireOperator(operator);
        throw new UnsupportedOperationException("凭证冲销（红字凭证）尚未实现，统一在 M4-T07 落地");
    }

    /** 按单据号查（不存在抛 {@link VoucherNotFoundException} → API 404） */
    public Voucher get(String docNo) {
        return repository.findByDocNo(Objects.requireNonNull(docNo, "单据号不能为空"))
                .orElseThrow(() -> new VoucherNotFoundException(docNo));
    }

    /** 分页查询 */
    public PageResult<Voucher> search(VoucherQuery query) {
        return repository.search(Objects.requireNonNull(query, "query 不能为空"));
    }

    // ---------------------------------------------------------------
    // 派生只读：试算平衡 / 科目余额（从已过账凭证行 SUM 派生，拆解 §8 决策 2）
    // ---------------------------------------------------------------

    /** 试算平衡：某账期已过账（APPROVED）凭证行按科目汇总借贷发生额（按科目编码升序）。 */
    public List<AccountBalance> trialBalance(String period) {
        return repository.aggregateBalances(Objects.requireNonNull(period, "账期不能为空").strip());
    }

    /** 某科目某账期已过账借贷余额（无发生额返回零额 {@link AccountBalance}）。 */
    public AccountBalance accountBalance(String accountCode, String period) {
        Objects.requireNonNull(accountCode, "科目编码不能为空");
        String normalizedCode = accountCode.strip();
        return trialBalance(period).stream()
                .filter(balance -> balance.accountCode().equals(normalizedCode))
                .findFirst()
                .orElse(new AccountBalance(normalizedCode, null, null));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("operator 不能为空");
        }
    }
}
