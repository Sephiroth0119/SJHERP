package com.sjherp.domain.fund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.InMemorySequenceProvider;
import com.sjherp.domain.gl.Account;
import com.sjherp.domain.gl.AccountRepository;
import com.sjherp.domain.gl.AccountType;
import com.sjherp.domain.gl.BalanceDirection;

/**
 * 资金账户档案领域服务单测（M4-T04a）：自动编号、编码唯一、glAccountCode 校验（核心新逻辑）、
 * 启停规则、@Audited 标注、accountType 各值、404。
 *
 * <p>用内存替身仓储（domain 模块仅 JUnit5，沿用 warehouse/gl 包手写 Fake 的约定）。
 * glAccountCode 校验依赖 {@link AccountRepository}——同样用 Fake 预置一个启用末级科目。
 */
class PaymentAccountServiceTest {

    private static final String OPERATOR = "tester";

    /** 已存在/启用/末级的 GL 货币科目编码（合法 glAccountCode） */
    private static final String VALID_GL = "1002";

    /** 固定时钟：2026-06，自动编号应为 FA-202606-XXXX */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-12T08:00:00Z"), ZoneOffset.UTC);

    private FakePaymentAccountRepository paymentAccountRepository;
    private FakeAccountRepository accountRepository;
    private PaymentAccountService service;

    @BeforeEach
    void setUp() {
        paymentAccountRepository = new FakePaymentAccountRepository();
        accountRepository = new FakeAccountRepository();
        // 预置一个合法（启用 + 末级）货币科目供 glAccountCode 校验通过
        accountRepository.put(leafEnabled(VALID_GL, "银行存款"));
        service = new PaymentAccountService(paymentAccountRepository,
                new DefaultDocumentNumberGenerator(new InMemorySequenceProvider(), FIXED_CLOCK),
                accountRepository);
    }

    private PaymentAccountCommand command(String code, String name) {
        return new PaymentAccountCommand(code, name, PaymentAccountType.BANK, VALID_GL,
                "工商银行嘉定支行", "6222021234567890");
    }

    // ----------------------------------------------------- 建档 / 自动编号

    @Test
    void 编码为空时自动编号_FA前缀年月序号() {
        PaymentAccount first = service.create(command(null, "基本户"), OPERATOR);
        PaymentAccount second = service.create(command("", "一般户"), OPERATOR);
        assertEquals("FA-202606-0001", first.getCode());
        assertEquals("FA-202606-0002", second.getCode());
        assertNotNull(first.getId());
        assertEquals(ArchiveStatus.ENABLED, first.getStatus());
        assertEquals(VALID_GL, first.getGlAccountCode());
    }

    @Test
    void 手填编码可用_重复被拒绝() {
        service.create(command("FA-BANK", "基本户"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(command("FA-BANK", "山寨户"), OPERATOR));
        assertTrue(e.getMessage().contains("已存在"), e.getMessage());
    }

    @Test
    void 名称为空被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> service.create(command(null, null), OPERATOR));
        assertThrows(IllegalArgumentException.class, () -> service.create(command(null, "  "), OPERATOR));
    }

    @Test
    void accountType各值均可建档() {
        PaymentAccount cash = service.create(
                new PaymentAccountCommand(null, "现金", PaymentAccountType.CASH, VALID_GL, null, null), OPERATOR);
        PaymentAccount bank = service.create(
                new PaymentAccountCommand(null, "银行", PaymentAccountType.BANK, VALID_GL, "工行", "62220"), OPERATOR);
        PaymentAccount other = service.create(
                new PaymentAccountCommand(null, "其他", PaymentAccountType.OTHER, VALID_GL, null, null), OPERATOR);
        assertEquals(PaymentAccountType.CASH, cash.getAccountType());
        assertEquals(PaymentAccountType.BANK, bank.getAccountType());
        assertEquals(PaymentAccountType.OTHER, other.getAccountType());
    }

    @Test
    void accountType为空被拒绝() {
        assertThrows(NullPointerException.class, () -> service.create(
                new PaymentAccountCommand(null, "无类别", null, VALID_GL, null, null), OPERATOR));
    }

    // ----------------------------------------------------- glAccountCode 校验（核心新逻辑）

    @Test
    void glAccountCode为空被拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create(
                new PaymentAccountCommand(null, "无科目", PaymentAccountType.BANK, null, null, null), OPERATOR));
        assertTrue(e.getMessage().contains("不能为空"), e.getMessage());
    }

    @Test
    void glAccountCode不存在被拒_精确报错() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create(
                new PaymentAccountCommand(null, "幽灵科目", PaymentAccountType.BANK, "9999", null, null), OPERATOR));
        assertEquals("GL 科目不存在: 9999", e.getMessage());
    }

    @Test
    void glAccountCode已停用被拒_精确报错() {
        accountRepository.put(leafDisabled("1001", "库存现金"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create(
                new PaymentAccountCommand(null, "停用科目", PaymentAccountType.CASH, "1001", null, null), OPERATOR));
        assertEquals("GL 科目已停用，不能用于资金账户: 1001", e.getMessage());
    }

    @Test
    void glAccountCode非末级被拒_精确报错() {
        accountRepository.put(nonLeafEnabled("1000", "货币资金（汇总）"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.create(
                new PaymentAccountCommand(null, "非末级科目", PaymentAccountType.BANK, "1000", null, null), OPERATOR));
        assertEquals("GL 科目不是末级科目，不能用于资金账户挂账: 1000", e.getMessage());
    }

    @Test
    void glAccountCode带空白会被规范化() {
        PaymentAccount account = service.create(
                new PaymentAccountCommand(null, "空白科目", PaymentAccountType.BANK, "  " + VALID_GL + "  ", null, null),
                OPERATOR);
        assertEquals(VALID_GL, account.getGlAccountCode());
    }

    // ----------------------------------------------------- 更新

    @Test
    void 更新可改编码_与他人重复被拒_与自己相同放行() {
        PaymentAccount first = service.create(command("FA-A", "基本户"), OPERATOR);
        service.create(command("FA-B", "一般户"), OPERATOR);

        // 改成他人编码 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.update(first.getId(), command("FA-B", "基本户"), OPERATOR));
        // 编码不变只改名 → 放行
        PaymentAccount updated = service.update(first.getId(), command("FA-A", "公司基本户"), OPERATOR);
        assertEquals("公司基本户", updated.getName());
    }

    @Test
    void 更新时编码为空被拒绝_不触发自动编号() {
        PaymentAccount account = service.create(command("FA-A", "基本户"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.update(account.getId(), command(null, "基本户"), OPERATOR));
        assertTrue(e.getMessage().contains("编码不能为空"), e.getMessage());
    }

    @Test
    void 更新时同样校验glAccountCode() {
        PaymentAccount account = service.create(command("FA-A", "基本户"), OPERATOR);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.update(
                account.getId(),
                new PaymentAccountCommand("FA-A", "基本户", PaymentAccountType.BANK, "9999", null, null), OPERATOR));
        assertEquals("GL 科目不存在: 9999", e.getMessage());
    }

    // ----------------------------------------------------- 启停规则

    @Test
    void 启停规则_停用再启用_重复操作被拒绝() {
        PaymentAccount account = service.create(command(null, "基本户"), OPERATOR);
        long id = account.getId();

        PaymentAccount disabled = service.disable(id, "boss");
        assertEquals(ArchiveStatus.DISABLED, disabled.getStatus());
        assertEquals("boss", disabled.getUpdatedBy());
        // 重复停用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.disable(id, "boss"));

        PaymentAccount enabled = service.enable(id, OPERATOR);
        assertEquals(ArchiveStatus.ENABLED, enabled.getStatus());
        // 重复启用 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> service.enable(id, OPERATOR));
    }

    // ----------------------------------------------------- 查询

    @Test
    void 查询不存在的资金账户抛404异常() {
        assertThrows(PaymentAccountNotFoundException.class, () -> service.get(999L));
    }

    @Test
    void 分页关键字查询_匹配编码名称开户行() {
        service.create(command("FA-RAW", "基本户"), OPERATOR);
        service.create(new PaymentAccountCommand("FA-FIN", "一般户", PaymentAccountType.BANK, VALID_GL,
                "建设银行", "62220999"), OPERATOR);

        assertEquals(1, service.search(new PaymentAccountQuery("基本", null, 1, 20)).total());
        assertEquals(1, service.search(new PaymentAccountQuery("FA-FIN", null, 1, 20)).total());
        assertEquals(1, service.search(new PaymentAccountQuery("建设银行", null, 1, 20)).total());
        assertEquals(2, service.search(new PaymentAccountQuery(null, null, 1, 20)).total());
    }

    @Test
    void 分页关键字查询_可按状态过滤() {
        PaymentAccount first = service.create(command("FA-RAW", "基本户"), OPERATOR);
        service.create(command("FA-FIN", "一般户"), OPERATOR);
        service.disable(first.getId(), OPERATOR);

        PageResult<PaymentAccount> enabled = service.search(
                new PaymentAccountQuery(null, ArchiveStatus.ENABLED, 1, 20));
        assertEquals(1, enabled.total());
        assertEquals("一般户", enabled.items().get(0).getName());
        assertEquals(1, service.search(new PaymentAccountQuery(null, ArchiveStatus.DISABLED, 1, 20)).total());
    }

    // ----------------------------------------------------- 审计

    @Test
    void 审计字段完整() {
        PaymentAccount account = service.create(command(null, "基本户"), OPERATOR);
        assertEquals(OPERATOR, account.getCreatedBy());
        assertNotNull(account.getCreatedAt());
        assertEquals(OPERATOR, account.getUpdatedBy());
        assertNotNull(account.getUpdatedAt());
    }

    @Test
    void 写方法均标注Audited切面() throws NoSuchMethodException {
        assertAudited("create", PaymentAccountCommand.class, String.class);
        assertAudited("update", long.class, PaymentAccountCommand.class, String.class);
        assertAudited("enable", long.class, String.class);
        assertAudited("disable", long.class, String.class);
    }

    private static void assertAudited(String name, Class<?>... paramTypes) throws NoSuchMethodException {
        Method method = PaymentAccountService.class.getMethod(name, paramTypes);
        Audited audited = method.getAnnotation(Audited.class);
        assertNotNull(audited, name + " 应标注 @Audited");
        assertEquals("payment_account", audited.targetType(), name + " 的 @Audited targetType");
    }

    // ----------------------------------------------------- GL 科目测试桩工厂

    private static Account leafEnabled(String code, String name) {
        return Account.restore(idFor(code), code, name, AccountType.ASSET, BalanceDirection.DEBIT,
                null, 1, true, true, true, "system", Instant.now(), "system", Instant.now());
    }

    private static Account leafDisabled(String code, String name) {
        return Account.restore(idFor(code), code, name, AccountType.ASSET, BalanceDirection.DEBIT,
                null, 1, true, false, false, "system", Instant.now(), "system", Instant.now());
    }

    private static Account nonLeafEnabled(String code, String name) {
        return Account.restore(idFor(code), code, name, AccountType.ASSET, BalanceDirection.DEBIT,
                null, 1, false, true, false, "system", Instant.now(), "system", Instant.now());
    }

    private static long idFor(String code) {
        return Math.abs(code.hashCode()) + 1L;
    }

    // ----------------------------------------------------- 内存替身仓储

    /** 资金账户内存仓储替身（仅测试使用） */
    private static final class FakePaymentAccountRepository implements PaymentAccountRepository {

        private final Map<Long, PaymentAccount> store = new LinkedHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(PaymentAccount account) {
            if (account.getId() == null) {
                account.assignId(idGen.incrementAndGet());
            }
            store.put(account.getId(), account);
        }

        @Override
        public Optional<PaymentAccount> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<PaymentAccount> findByCode(String code) {
            return store.values().stream().filter(a -> a.getCode().equals(code)).findFirst();
        }

        @Override
        public boolean existsByCode(String code) {
            return store.values().stream().anyMatch(a -> a.getCode().equals(code));
        }

        @Override
        public PageResult<PaymentAccount> search(PaymentAccountQuery query) {
            List<PaymentAccount> matched = store.values().stream()
                    .filter(a -> query.status() == null || a.getStatus() == query.status())
                    .filter(a -> matchesKeyword(a, query.keyword()))
                    .sorted(Comparator.comparing(PaymentAccount::getId).reversed())
                    .toList();
            int from = Math.min((query.page() - 1) * query.size(), matched.size());
            int to = Math.min(from + query.size(), matched.size());
            return new PageResult<>(new ArrayList<>(matched.subList(from, to)),
                    matched.size(), query.page(), query.size());
        }

        private static boolean matchesKeyword(PaymentAccount a, String keyword) {
            if (keyword == null) {
                return true;
            }
            String kw = keyword.toLowerCase(Locale.ROOT);
            return a.getCode().toLowerCase(Locale.ROOT).contains(kw)
                    || a.getName().toLowerCase(Locale.ROOT).contains(kw)
                    || (a.getBankName() != null && a.getBankName().toLowerCase(Locale.ROOT).contains(kw));
        }
    }

    /** GL 科目内存仓储替身（仅 glAccountCode 校验需要 findByCode） */
    private static final class FakeAccountRepository implements AccountRepository {

        private final Map<String, Account> store = new LinkedHashMap<>();

        void put(Account account) {
            store.put(account.getCode(), account);
        }

        @Override
        public void save(Account account) {
            store.put(account.getCode(), account);
        }

        @Override
        public Optional<Account> findByCode(String code) {
            return Optional.ofNullable(store.get(code));
        }

        @Override
        public List<Account> findAll() {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<Account> findLeaf() {
            return store.values().stream().filter(Account::isLeaf).toList();
        }

        @Override
        public boolean existsByCode(String code) {
            return store.containsKey(code);
        }
    }
}
