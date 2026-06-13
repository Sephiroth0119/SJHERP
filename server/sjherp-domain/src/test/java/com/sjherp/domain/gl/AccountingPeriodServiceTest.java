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
 * 会计期间领域服务单测（M4-T01，拆解 §7）：开账（重复拒绝）、关账、重开、isOpen、404。
 * 用内存替身仓储（domain 模块仅 JUnit5，沿用手写 Fake 约定）。
 */
class AccountingPeriodServiceTest {

    private static final String OPERATOR = "tester";

    private FakeAccountingPeriodRepository repository;
    private AccountingPeriodService service;

    @BeforeEach
    void setUp() {
        repository = new FakeAccountingPeriodRepository();
        service = new AccountingPeriodService(repository);
    }

    // ----------------------------------------------------- 开账

    @Test
    void 开账_新账期为开启() {
        AccountingPeriod period = service.open("202606", OPERATOR);
        assertEquals(PeriodStatus.OPEN, period.getStatus());
        assertTrue(service.isOpen("202606"));
    }

    @Test
    void 重复开账被拒() {
        service.open("202606", OPERATOR);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.open("202606", OPERATOR));
        assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
    }

    // ----------------------------------------------------- 关账 / 重开

    @Test
    void 关账后isOpen为假() {
        service.open("202606", OPERATOR);
        AccountingPeriod closed = service.close("202606", "accountant");
        assertEquals(PeriodStatus.CLOSED, closed.getStatus());
        assertFalse(service.isOpen("202606"));
        assertEquals("accountant", closed.getClosedBy());
    }

    @Test
    void 重开后isOpen恢复为真() {
        service.open("202606", OPERATOR);
        service.close("202606", OPERATOR);
        service.reopen("202606", "boss");
        assertTrue(service.isOpen("202606"));
    }

    @Test
    void 关不存在账期抛NotFound() {
        assertThrows(AccountingPeriodNotFoundException.class, () -> service.close("202606", OPERATOR));
    }

    @Test
    void 重开不存在账期抛NotFound() {
        assertThrows(AccountingPeriodNotFoundException.class, () -> service.reopen("202606", OPERATOR));
    }

    // ----------------------------------------------------- isOpen / 查询

    @Test
    void isOpen_账期不存在返回假() {
        assertFalse(service.isOpen("209901"));
    }

    @Test
    void 查不存在抛NotFound() {
        assertThrows(AccountingPeriodNotFoundException.class, () -> service.get("209901"));
    }

    @Test
    void 列出全部按账期升序() {
        service.open("202607", OPERATOR);
        service.open("202606", OPERATOR);
        List<AccountingPeriod> all = service.listAll();
        assertEquals(2, all.size());
        assertEquals("202606", all.get(0).getPeriod());
        assertEquals("202607", all.get(1).getPeriod());
    }

    // ----------------------------------------------------- 内存替身仓储

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
            List<AccountingPeriod> all = new ArrayList<>(store.values());
            all.sort(Comparator.comparing(AccountingPeriod::getPeriod));
            return all;
        }
    }
}
