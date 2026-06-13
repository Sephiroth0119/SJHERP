package com.sjherp.domain.receivable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.PageResult;

/**
 * 应收账款领域服务单测（M3-T10）：开票生成应收（OPEN）、同来源单据号幂等、未核销余额派生、
 * 金额校验、BigDecimal 精度。用内存替身仓储。
 */
class ReceivableServiceTest {

    private static final long CUSTOMER = 7L;
    private static final String OPERATOR = "tester";
    private static final LocalDate DUE = LocalDate.of(2026, 7, 14);

    private FakeReceivableRepository repository;
    private ReceivableService service;

    @BeforeEach
    void setUp() {
        repository = new FakeReceivableRepository();
        service = new ReceivableService(repository);
    }

    @Test
    void 开票生成应收_OPEN_未核销余额等于金额() {
        AccountsReceivable ar = service.open(CUSTOMER, new BigDecimal("1750.00"), "SINV-1", DUE, OPERATOR);
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
        assertEquals(CUSTOMER, ar.getCustomerId());
        assertEqualsDecimal("1750.00", ar.getAmount());
        assertEqualsDecimal("0", ar.getSettledAmount());
        assertEqualsDecimal("1750.00", ar.openAmount());
        assertEquals("SINV-1", ar.getSourceDocNo());
        assertEquals(DUE, ar.getDueDate());
    }

    @Test
    void 同来源单据号幂等_返回首条不重复挂账() {
        AccountsReceivable first = service.open(CUSTOMER, new BigDecimal("100.00"), "SINV-1", DUE, OPERATOR);
        AccountsReceivable again = service.open(CUSTOMER, new BigDecimal("100.00"), "SINV-1", DUE, OPERATOR);
        assertSame(first, again);
        assertEquals(1, repository.store.size());
    }

    @Test
    void 金额可为零_不可为负() {
        AccountsReceivable zero = service.open(CUSTOMER, new BigDecimal("0.00"), "SINV-Z", null, OPERATOR);
        assertEqualsDecimal("0.00", zero.getAmount());
        assertThrows(IllegalArgumentException.class,
                () -> service.open(CUSTOMER, new BigDecimal("-1"), "SINV-N", null, OPERATOR));
    }

    @Test
    void 到期日可空() {
        AccountsReceivable ar = service.open(CUSTOMER, new BigDecimal("50.00"), "SINV-2", null, OPERATOR);
        assertEquals(null, ar.getDueDate());
    }

    @Test
    void 查询不存在抛NotFound() {
        assertThrows(ReceivableNotFoundException.class, () -> service.get(999L));
    }

    @Test
    void operator为空拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.open(CUSTOMER, new BigDecimal("1"), "SINV-1", null, " "));
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static final class FakeReceivableRepository implements ReceivableRepository {

        private final java.util.Map<Long, AccountsReceivable> store = new java.util.HashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public void save(AccountsReceivable receivable) {
            if (receivable.getId() == null) {
                receivable.assignId(idGen.incrementAndGet());
            }
            store.put(receivable.getId(), receivable);
        }

        @Override
        public List<AccountsReceivable> findBySourceDocNo(String sourceDocNo) {
            List<AccountsReceivable> result = new ArrayList<>();
            for (AccountsReceivable ar : store.values()) {
                if (ar.getSourceDocNo().equals(sourceDocNo)) {
                    result.add(ar);
                }
            }
            return result;
        }

        @Override
        public Optional<AccountsReceivable> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public PageResult<AccountsReceivable> search(ReceivableQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }
}
