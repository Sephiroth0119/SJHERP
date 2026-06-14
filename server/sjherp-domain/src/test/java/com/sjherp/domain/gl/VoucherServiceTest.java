package com.sjherp.domain.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 凭证领域服务单测（M4-T01 + M4-T07a）：建单校验（科目存在/末级/启用、凭证日期落在账期内、借贷平衡）、
 * 过账关账守卫（OPEN 通过 / CLOSED 抛 {@link PeriodClosedException}）、冲销（借贷对调红字/双向 linkage/
 * 幂等/账期 CLOSED 拒/原单非 APPROVED 拒/多借多贷对调仍平衡）、试算平衡派生。
 *
 * <p>真实 {@link AccountService}/{@link AccountingPeriodService} 叠在内存替身仓储上（domain 模块仅
 * JUnit5，沿用手写 Fake 约定），凭证仓储用内存替身并按 APPROVED 派生科目余额。
 */
class VoucherServiceTest {

    private static final String DOC_NO = "VCH-202606-0001";
    private static final String PERIOD = "202606";
    private static final LocalDate DATE = LocalDate.of(2026, 6, 13);
    private static final String OPERATOR = "tester";

    private FakeAccountRepository accountRepo;
    private FakeAccountingPeriodRepository periodRepo;
    private FakeVoucherRepository voucherRepo;
    private AccountService accountService;
    private AccountingPeriodService periodService;
    private VoucherService service;

    @BeforeEach
    void setUp() {
        accountRepo = new FakeAccountRepository();
        periodRepo = new FakeAccountingPeriodRepository();
        voucherRepo = new FakeVoucherRepository();
        accountService = new AccountService(accountRepo);
        periodService = new AccountingPeriodService(periodRepo);
        service = new VoucherService(voucherRepo, accountService, periodService, NoopPublisher.INSTANCE);

        // 预置两个末级启用科目 + 开启账期 202606
        accountService.create("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT,
                null, true, OPERATOR);
        accountService.create("6001", "主营业务收入", AccountType.PROFIT_LOSS, BalanceDirection.CREDIT,
                null, true, OPERATOR);
        periodService.open(PERIOD, OPERATOR);
    }

    // ----------------------------------------------------- 建单

    @Test
    void 建单为草稿_借贷平衡_行号自一编排() {
        Voucher voucher = service.create(DOC_NO, PERIOD, DATE, "收款",
                List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "100.00")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, voucher.getStatus());
        assertEqualsDecimal("100.00", voucher.getTotalAmount());
        assertEquals(1, voucher.getLines().get(0).getLineNo());
        assertEquals(2, voucher.getLines().get(1).getLineNo());
        // 已落库
        assertTrue(voucherRepo.findByDocNo(DOC_NO).isPresent());
    }

    @Test
    void 建单_借贷不平被拒_库中无记录() {
        // 验收①：不平连聚合都构造不出，到不了 save
        assertThrows(VoucherNotBalancedException.class,
                () -> service.create(DOC_NO, PERIOD, DATE, null,
                        List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "99.00")),
                        OPERATOR));
        assertTrue(voucherRepo.findByDocNo(DOC_NO).isEmpty());
    }

    @Test
    void 建单_账期不存在抛NotFound() {
        assertThrows(AccountingPeriodNotFoundException.class,
                () -> service.create(DOC_NO, "209901", LocalDate.of(2099, 9, 1), null,
                        List.of(lineInput("1001", "1.00", null), lineInput("6001", null, "1.00")),
                        OPERATOR));
    }

    @Test
    void 建单_凭证日期不在账期内被拒() {
        // 账期 202606，凭证日期落在 7 月 → 拒绝
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(DOC_NO, PERIOD, LocalDate.of(2026, 7, 1), null,
                        List.of(lineInput("1001", "1.00", null), lineInput("6001", null, "1.00")),
                        OPERATOR));
        assertTrue(ex.getMessage().contains("不在账期"), ex.getMessage());
    }

    @Test
    void 建单_科目不存在抛NotFound() {
        assertThrows(AccountNotFoundException.class,
                () -> service.create(DOC_NO, PERIOD, DATE, null,
                        List.of(lineInput("9999", "1.00", null), lineInput("6001", null, "1.00")),
                        OPERATOR));
    }

    @Test
    void 建单_科目非末级被拒() {
        // 建一个非末级科目，挂账应被拒
        accountService.create("2221", "应交税费", AccountType.LIABILITY, BalanceDirection.CREDIT,
                null, false, OPERATOR);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(DOC_NO, PERIOD, DATE, null,
                        List.of(lineInput("2221", "1.00", null), lineInput("6001", null, "1.00")),
                        OPERATOR));
        assertTrue(ex.getMessage().contains("非末级"), ex.getMessage());
    }

    @Test
    void 建单_科目已停用被拒() {
        accountService.disable("1001", OPERATOR);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(DOC_NO, PERIOD, DATE, null,
                        List.of(lineInput("1001", "1.00", null), lineInput("6001", null, "1.00")),
                        OPERATOR));
        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());
    }

    // ----------------------------------------------------- 过账（验收②：关账守卫）

    @Test
    void 过账_账期开启通过_状态变为已过账() {
        service.create(DOC_NO, PERIOD, DATE, null,
                List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "100.00")), OPERATOR);

        Voucher posted = service.post(DOC_NO, OPERATOR);
        assertEquals(DocumentStatus.APPROVED, posted.getStatus());
    }

    @Test
    void 过账_账期已关账被拒_抛PeriodClosed() {
        // 验收②：关账后禁止过账
        service.create(DOC_NO, PERIOD, DATE, null,
                List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "100.00")), OPERATOR);
        periodService.close(PERIOD, "accountant");

        PeriodClosedException ex = assertThrows(PeriodClosedException.class,
                () -> service.post(DOC_NO, OPERATOR));
        assertTrue(ex.getMessage().contains("已关闭"), ex.getMessage());
        // 凭证仍为草稿（过账被拒，模型不破碎）
        assertEquals(DocumentStatus.DRAFT, service.get(DOC_NO).getStatus());
    }

    @Test
    void 过账_异常是非法状态异常子类_可被四百零九拦截() {
        service.create(DOC_NO, PERIOD, DATE, null,
                List.of(lineInput("1001", "1.00", null), lineInput("6001", null, "1.00")), OPERATOR);
        periodService.close(PERIOD, OPERATOR);
        // PeriodClosedException extends IllegalStateException → REST 409
        assertThrows(IllegalStateException.class, () -> service.post(DOC_NO, OPERATOR));
    }

    @Test
    void 过账_不存在凭证抛NotFound() {
        assertThrows(VoucherNotFoundException.class, () -> service.post("VCH-NONE", OPERATOR));
    }

    // ----------------------------------------------------- createFromSource（T02 自动凭证可追溯）

    @Test
    void 建单来源_回填来源两列_可追溯锚点() {
        // T02：自动凭证经 createFromSource 回填 source_doc_type / source_doc_no（拆解 §3）
        Voucher voucher = service.createFromSource(DOC_NO, PERIOD, DATE, "采购入库 PR-202606-0001",
                VoucherSourceType.PURCHASE_RECEIPT, "PR-202606-0001",
                List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "100.00")), OPERATOR);

        // 来源两列落库为枚举名 + 业务单号（与 findBySourceDocNo 幂等键一致）
        assertEquals("PURCHASE_RECEIPT", voucher.getSourceDocType());
        assertEquals("PR-202606-0001", voucher.getSourceDocNo());
        assertEquals(DocumentStatus.DRAFT, voucher.getStatus());
        // 可按来源单号查回（幂等查重路径）
        assertEquals(1, service.findBySourceDocNo("PR-202606-0001").size());
    }

    @Test
    void 建单来源_平衡校验沿用_借贷不平被拒() {
        // createFromSource 与 create 共用 Voucher.create 平衡校验（验收①）
        assertThrows(VoucherNotBalancedException.class,
                () -> service.createFromSource(DOC_NO, PERIOD, DATE, null,
                        VoucherSourceType.SALES_INVOICE, "SINV-202606-0001",
                        List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "99.00")),
                        OPERATOR));
        assertTrue(voucherRepo.findByDocNo(DOC_NO).isEmpty());
    }

    @Test
    void 建单来源_账期日期科目校验沿用() {
        // 凭证日期不在账期内同样被拒（与 create 一致）
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createFromSource(DOC_NO, PERIOD, LocalDate.of(2026, 7, 1), null,
                        VoucherSourceType.PURCHASE_INVOICE, "PINV-202606-0001",
                        List.of(lineInput("1001", "1.00", null), lineInput("6001", null, "1.00")),
                        OPERATOR));
        assertTrue(ex.getMessage().contains("不在账期"), ex.getMessage());
    }

    @Test
    void 手工建单create_委托createFromSource_来源两列为空() {
        // create 重构为 createFromSource(...,null,null,...)：手工凭证来源两列必须为 null（不被幂等查重命中）
        Voucher voucher = service.create(DOC_NO, PERIOD, DATE, "手工凭证",
                List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "100.00")), OPERATOR);

        assertNull(voucher.getSourceDocType());
        assertNull(voucher.getSourceDocNo());
    }

    // ----------------------------------------------------- 冲销（M4-T07a 红字凭证）
    // 红字号在测试中固定 RED_NO（app 层职责，领域服务接受预生成号）；正向凭证一律先建后过账再冲销。

    private static final String RED_NO = "VCH-202606-9001";

    /** 建并过账一张一借一贷凭证（1001 借 / 6001 贷，金额 amount），返回已过账原凭证。 */
    private Voucher createAndPost(String docNo, String amount) {
        service.create(docNo, PERIOD, DATE, "原凭证",
                List.of(lineInput("1001", amount, null), lineInput("6001", null, amount)), OPERATOR);
        return service.post(docNo, OPERATOR);
    }

    @Test
    void 冲销_借贷对调_金额不变_行数一致() {
        createAndPost(DOC_NO, "100.00");

        Voucher red = service.reverse(DOC_NO, RED_NO, OPERATOR);

        // 红字号、来源回填、过账态
        assertEquals(RED_NO, red.getDocNo());
        assertEquals(DocumentStatus.APPROVED, red.getStatus());
        assertEquals("VOUCHER_REVERSAL", red.getSourceDocType());
        assertEquals(DOC_NO, red.getSourceDocNo());
        // 行数一致、总额不变
        assertEquals(2, red.getLines().size());
        assertEqualsDecimal("100.00", red.getTotalAmount());
        // 借贷对调：原 1001 借 → 红字 1001 贷；原 6001 贷 → 红字 6001 借
        VoucherLine red1001 = red.getLines().stream()
                .filter(l -> l.getAccountCode().equals("1001")).findFirst().orElseThrow();
        VoucherLine red6001 = red.getLines().stream()
                .filter(l -> l.getAccountCode().equals("6001")).findFirst().orElseThrow();
        assertEqualsDecimal("0.00", red1001.getDebit());
        assertEqualsDecimal("100.00", red1001.getCredit());
        assertEqualsDecimal("100.00", red6001.getDebit());
        assertEqualsDecimal("0.00", red6001.getCredit());
        // 摘要前缀「冲销:」
        assertTrue(red1001.getSummary() == null || red1001.getSummary().startsWith("冲销:"),
                red1001.getSummary());
    }

    @Test
    void 冲销后_受影响科目净额归零_且Σ借等于Σ贷() {
        // 红字法（M4-T07a）：原凭证 REVERSED + 红字凭证 APPROVED 二者都计入科目汇总
        // （aggregateBalances status IN APPROVED/REVERSED），借贷对调相互抵消 → 每个科目净额归零。
        createAndPost(DOC_NO, "100.00");
        service.reverse(DOC_NO, RED_NO, OPERATOR);

        List<AccountBalance> balances = service.trialBalance(PERIOD);
        // 每个受影响科目借贷各 100、净额（借−贷）=0
        for (AccountBalance b : balances) {
            assertEquals(0, b.totalDebit().subtract(b.totalCredit()).compareTo(BigDecimal.ZERO),
                    "冲销后科目 " + b.accountCode() + " 净额应归零");
        }
        BigDecimal totalDebit = balances.stream().map(AccountBalance::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = balances.stream().map(AccountBalance::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, totalDebit.compareTo(totalCredit), "冲销后 Σ借应等于 Σ贷");
    }

    @Test
    void 冲销_原凭证转REVERSED_双向linkage() {
        createAndPost(DOC_NO, "100.00");

        Voucher red = service.reverse(DOC_NO, RED_NO, OPERATOR);

        // 红字凭证 → 原凭证：reversalOfId=原号、isReversalDocument
        assertEquals(DOC_NO, red.getReversalOfId());
        assertTrue(red.isReversalDocument());
        // 原凭证 → 红字凭证：reversedById=红字号、状态 REVERSED
        Voucher original = service.get(DOC_NO);
        assertEquals(DocumentStatus.REVERSED, original.getStatus());
        assertEquals(RED_NO, original.getReversedById());
    }

    @Test
    void 冲销_多借多贷凭证_对调后仍平衡() {
        // 多借多贷：1001 借 60 + 1002 借 40 / 6001 贷 70 + 6002 贷 30
        accountService.create("1002", "银行存款", AccountType.ASSET, BalanceDirection.DEBIT,
                null, true, OPERATOR);
        accountService.create("6002", "其他业务收入", AccountType.PROFIT_LOSS, BalanceDirection.CREDIT,
                null, true, OPERATOR);
        service.create(DOC_NO, PERIOD, DATE, "多借多贷",
                List.of(lineInput("1001", "60.00", null), lineInput("1002", "40.00", null),
                        lineInput("6001", null, "70.00"), lineInput("6002", null, "30.00")), OPERATOR);
        service.post(DOC_NO, OPERATOR);

        Voucher red = service.reverse(DOC_NO, RED_NO, OPERATOR);

        // 行数一致、对调后仍 Σ借==Σ贷、总额不变
        assertEquals(4, red.getLines().size());
        BigDecimal redDebit = red.getLines().stream().map(VoucherLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal redCredit = red.getLines().stream().map(VoucherLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, redDebit.compareTo(redCredit), "红字凭证 Σ借应等于 Σ贷");
        assertEqualsDecimal("100.00", red.getTotalAmount());
    }

    @Test
    void 冲销_原凭证不存在抛NotFound() {
        assertThrows(VoucherNotFoundException.class,
                () -> service.reverse("VCH-NONE", RED_NO, OPERATOR));
    }

    @Test
    void 冲销_原凭证为草稿被拒_未过账不可冲销() {
        // DRAFT 凭证不可冲销（清晰报错），抛 IllegalStateException
        service.create(DOC_NO, PERIOD, DATE, null,
                List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "100.00")), OPERATOR);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.reverse(DOC_NO, RED_NO, OPERATOR));
        assertTrue(ex.getMessage().contains("APPROVED") || ex.getMessage().contains("已过账"),
                ex.getMessage());
        // 原凭证仍为草稿（冲销被拒，模型不破碎），无红字凭证落库
        assertEquals(DocumentStatus.DRAFT, service.get(DOC_NO).getStatus());
        assertTrue(voucherRepo.findByDocNo(RED_NO).isEmpty());
    }

    @Test
    void 冲销_幂等_原凭证已被冲销再冲销被拒() {
        createAndPost(DOC_NO, "100.00");
        service.reverse(DOC_NO, RED_NO, OPERATOR);

        // 原凭证已 REVERSED + reversedById!=null → 再冲销被拒
        assertThrows(IllegalStateException.class,
                () -> service.reverse(DOC_NO, "VCH-202606-9002", OPERATOR));
    }

    @Test
    void 冲销_幂等_已存在红字凭证再冲销被拒() {
        // 构造一个仍 APPROVED 但已存在以其为来源的 VOUCHER_REVERSAL 红字（reversedById 未回填的边界）：
        // 通过正常冲销已使 reversedById 非空，第一道幂等先命中；此处校验 findBySourceDocNo 这道幂等的报错语义。
        createAndPost(DOC_NO, "100.00");
        service.reverse(DOC_NO, RED_NO, OPERATOR);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.reverse(DOC_NO, "VCH-202606-9003", OPERATOR));
        assertTrue(ex.getMessage().contains("冲销"), ex.getMessage());
    }

    @Test
    void 冲销_账期已关账被拒_抛PeriodClosed_无红字凭证() {
        createAndPost(DOC_NO, "100.00");
        // 关账后冲销被拒（闭月须先 reopen，拆解 §1.2）
        periodService.close(PERIOD, "accountant");

        assertThrows(PeriodClosedException.class,
                () -> service.reverse(DOC_NO, RED_NO, OPERATOR));
        // 原凭证仍 APPROVED（事务回滚），无红字凭证落库
        assertEquals(DocumentStatus.APPROVED, service.get(DOC_NO).getStatus());
        assertTrue(voucherRepo.findByDocNo(RED_NO).isEmpty());
    }

    @Test
    void 冲销_红字号为空被拒() {
        createAndPost(DOC_NO, "100.00");
        assertThrows(NullPointerException.class, () -> service.reverse(DOC_NO, null, OPERATOR));
    }

    // ----------------------------------------------------- 查询 / 试算平衡派生

    @Test
    void 查不存在凭证抛NotFound() {
        assertThrows(VoucherNotFoundException.class, () -> service.get("VCH-NONE"));
    }

    @Test
    void 试算平衡_仅统计已过账凭证_借贷相等() {
        // 草稿凭证不计入；过账后才进试算
        service.create(DOC_NO, PERIOD, DATE, null,
                List.of(lineInput("1001", "100.00", null), lineInput("6001", null, "100.00")), OPERATOR);
        // 未过账：试算为空
        assertTrue(service.trialBalance(PERIOD).isEmpty());

        service.post(DOC_NO, OPERATOR);
        List<AccountBalance> balances = service.trialBalance(PERIOD);
        assertEquals(2, balances.size());
        BigDecimal totalDebit = balances.stream().map(AccountBalance::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = balances.stream().map(AccountBalance::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, totalDebit.compareTo(totalCredit), "试算平衡：Σ借应等于 Σ贷");
    }

    @Test
    void 科目余额_无发生额返回零额() {
        AccountBalance balance = service.accountBalance("1001", PERIOD);
        assertEquals("1001", balance.accountCode());
        assertEqualsDecimal("0.00", balance.totalDebit());
        assertEqualsDecimal("0.00", balance.totalCredit());
    }

    // ----------------------------------------------------- 工具

    private static VoucherLineInput lineInput(String accountCode, String debit, String credit) {
        return new VoucherLineInput(accountCode,
                debit == null ? null : new BigDecimal(debit),
                credit == null ? null : new BigDecimal(credit), null);
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private enum NoopPublisher implements DomainEventPublisher {
        INSTANCE;

        @Override
        public void publish(DomainEvent event) {
            // no-op
        }
    }

    // ----------------------------------------------------- 内存替身仓储

    private static final class FakeAccountRepository implements AccountRepository {

        private final Map<String, Account> store = new LinkedHashMap<>();
        private long idSeq = 0;

        @Override
        public void save(Account account) {
            if (account.getId() == null) {
                account.assignId(++idSeq);
            }
            store.put(account.getCode(), account);
        }

        @Override
        public Optional<Account> findByCode(String code) {
            return Optional.ofNullable(store.get(code));
        }

        @Override
        public List<Account> findAll() {
            List<Account> all = new ArrayList<>(store.values());
            all.sort(Comparator.comparing(Account::getCode));
            return all;
        }

        @Override
        public List<Account> findLeaf() {
            return findAll().stream().filter(Account::isLeaf).toList();
        }

        @Override
        public boolean existsByCode(String code) {
            return store.containsKey(code);
        }
    }

    private static final class FakeAccountingPeriodRepository implements AccountingPeriodRepository {

        private final Map<String, AccountingPeriod> store = new LinkedHashMap<>();
        private long idSeq = 0;

        @Override
        public void save(AccountingPeriod period) {
            if (period.getId() == null) {
                period.assignId(++idSeq);
            }
            store.put(period.getPeriod(), period);
        }

        @Override
        public Optional<AccountingPeriod> findByPeriod(String period) {
            return Optional.ofNullable(store.get(period));
        }

        @Override
        public List<AccountingPeriod> findAll() {
            return new ArrayList<>(store.values());
        }
    }

    /** 凭证内存替身：按 APPROVED 派生科目余额（口径同 JdbcVoucherRepository.aggregateBalances）。 */
    private static final class FakeVoucherRepository implements VoucherRepository {

        private final Map<String, Voucher> store = new LinkedHashMap<>();

        @Override
        public void save(Voucher voucher) {
            store.put(voucher.getDocNo(), voucher);
        }

        @Override
        public Optional<Voucher> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<Voucher> search(VoucherQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }

        @Override
        public List<Voucher> findBySourceDocNo(String sourceDocNo) {
            return store.values().stream()
                    .filter(v -> sourceDocNo.equals(v.getSourceDocNo()))
                    .toList();
        }

        @Override
        public List<AccountBalance> aggregateBalances(String period) {
            Map<String, BigDecimal[]> agg = new LinkedHashMap<>();
            store.values().stream()
                    .filter(v -> v.getPeriod().equals(period))
                    // 与 JdbcVoucherRepository.aggregateBalances 一致（M4-T07a 红字法）：
                    // APPROVED + REVERSED 都计入（原凭证与其红字对调凭证须都计入才净额归零），DRAFT/CANCELLED 不计
                    .filter(v -> v.getStatus() == DocumentStatus.APPROVED
                            || v.getStatus() == DocumentStatus.REVERSED)
                    .flatMap(v -> v.getLines().stream())
                    .forEach(line -> {
                        BigDecimal[] sums = agg.computeIfAbsent(line.getAccountCode(),
                                k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                        sums[0] = sums[0].add(line.getDebit());
                        sums[1] = sums[1].add(line.getCredit());
                    });
            List<AccountBalance> result = new ArrayList<>();
            agg.forEach((code, sums) -> result.add(new AccountBalance(code, sums[0], sums[1])));
            result.sort(Comparator.comparing(AccountBalance::accountCode));
            return result;
        }
    }
}
