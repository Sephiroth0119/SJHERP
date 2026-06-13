package com.sjherp.domain.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 会计科目领域服务单测（M4-T01，拆解 §7）：编码唯一、上级存在且非末级、层级由上级推算、启停规则、404。
 * 用内存替身仓储（domain 模块仅 JUnit5，沿用 purchase 包手写 Fake 的约定）。
 */
class AccountServiceTest {

    private static final String OPERATOR = "tester";

    private FakeAccountRepository repository;
    private AccountService service;

    @BeforeEach
    void setUp() {
        repository = new FakeAccountRepository();
        service = new AccountService(repository);
    }

    // ----------------------------------------------------- 建档

    @Test
    void 新建一级科目_层级为一() {
        Account account = service.create("1001", "库存现金", AccountType.ASSET,
                BalanceDirection.DEBIT, null, true, OPERATOR);
        assertEquals(1, account.getLevel());
        assertTrue(repository.findByCode("1001").isPresent());
    }

    @Test
    void 新建二级科目_层级由上级推算() {
        // 先建一级非末级父科目
        service.create("2221", "应交税费", AccountType.LIABILITY, BalanceDirection.CREDIT,
                null, false, OPERATOR);
        Account child = service.create("222101", "应交税费—应交增值税", AccountType.LIABILITY,
                BalanceDirection.CREDIT, "2221", true, OPERATOR);
        assertEquals(2, child.getLevel());
        assertEquals("2221", child.getParentCode());
    }

    @Test
    void 编码已存在被拒() {
        service.create("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT, null, true, OPERATOR);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("1001", "重复", AccountType.ASSET, BalanceDirection.DEBIT,
                        null, true, OPERATOR));
        assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
    }

    @Test
    void 上级科目是末级被拒() {
        // 父科目是末级（不可再挂子科目）
        service.create("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT, null, true, OPERATOR);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("100101", "子", AccountType.ASSET, BalanceDirection.DEBIT,
                        "1001", true, OPERATOR));
        assertTrue(ex.getMessage().contains("末级"), ex.getMessage());
    }

    @Test
    void 上级科目不存在抛NotFound() {
        assertThrows(AccountNotFoundException.class,
                () -> service.create("100101", "子", AccountType.ASSET, BalanceDirection.DEBIT,
                        "9999", true, OPERATOR));
    }

    @Test
    void operator为空被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("1001", "库存现金", AccountType.ASSET, BalanceDirection.DEBIT,
                        null, true, " "));
    }

    // ----------------------------------------------------- 启停

    @Test
    void 停用再启用() {
        service.create("6601", "销售费用", AccountType.PROFIT_LOSS, BalanceDirection.DEBIT,
                null, true, OPERATOR);
        Account disabled = service.disable("6601", OPERATOR);
        assertFalse(disabled.isEnabled());
        Account enabled = service.enable("6601", OPERATOR);
        assertTrue(enabled.isEnabled());
    }

    @Test
    void 停用不存在科目抛NotFound() {
        assertThrows(AccountNotFoundException.class, () -> service.disable("9999", OPERATOR));
    }

    // ----------------------------------------------------- 查询

    @Test
    void 查不存在抛NotFound() {
        assertThrows(AccountNotFoundException.class, () -> service.get("9999"));
    }

    @Test
    void 列出全部与末级() {
        service.create("2221", "应交税费", AccountType.LIABILITY, BalanceDirection.CREDIT,
                null, false, OPERATOR);
        service.create("222101", "增值税", AccountType.LIABILITY, BalanceDirection.CREDIT,
                "2221", true, OPERATOR);
        assertEquals(2, service.listAll().size());
        // 仅末级（222101）
        List<Account> leaves = service.listLeaf();
        assertEquals(1, leaves.size());
        assertEquals("222101", leaves.get(0).getCode());
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
}
