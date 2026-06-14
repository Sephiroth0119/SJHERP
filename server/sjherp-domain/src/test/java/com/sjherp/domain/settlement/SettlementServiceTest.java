package com.sjherp.domain.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableQuery;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payable.PayableNotFoundException;
import com.sjherp.domain.payable.PayableStatus;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableNotFoundException;
import com.sjherp.domain.receivable.ReceivableQuery;
import com.sjherp.domain.receivable.ReceivableRepository;
import com.sjherp.domain.receivable.ReceivableStatus;

/**
 * 核销引擎领域服务单测（M4-T03）：
 * <ul>
 *   <li>settleReceivable / settlePayable 流程 = 装载 → settle → save(UPDATE 路径) → 落 SettlementRecord；</li>
 *   <li>核销记录字段（type/targetId/targetSourceDocNo/amount/settlementDate/paymentDocNo/createdBy=operator）；</li>
 *   <li>AR/AP 不存在 → 对应 NotFound；超额 → OverSettlementException（子账拒绝，不落记录）；</li>
 *   <li>operator/settlementDate 空校验；只读方法委派 findByTarget；@Audited 注解存在。</li>
 * </ul>
 *
 * <p>沿用本仓 domain 单测风格：手写内存替身仓储（无 Mockito，domain 测试 classpath 仅 JUnit5），
 * 替身记录最后一次 save 的对象供断言（等价 ArgumentCaptor）。
 */
class SettlementServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 14);
    private static final String OPERATOR = "tester";
    private static final LocalDate DUE = LocalDate.of(2026, 7, 14);

    private FakeReceivableRepository receivableRepo;
    private FakePayableRepository payableRepo;
    private FakeSettlementRecordRepository settlementRepo;
    private SettlementService service;

    @BeforeEach
    void setUp() {
        receivableRepo = new FakeReceivableRepository();
        payableRepo = new FakePayableRepository();
        settlementRepo = new FakeSettlementRecordRepository();
        service = new SettlementService(receivableRepo, payableRepo, settlementRepo);
    }

    // ---------------- 应收核销 ----------------

    @Test
    void 应收核销_装载_settle_save_落记录_字段正确() {
        AccountsReceivable ar = AccountsReceivable.restore(10L, 7L, new BigDecimal("1000.00"),
                new BigDecimal("0.00"), "SINV-1", DUE, ReceivableStatus.OPEN, "creator");
        receivableRepo.seed(ar);

        SettlementRecord rec = service.settleReceivable(10L, new BigDecimal("400.00"),
                DATE, "PAY-1", OPERATOR);

        // 子账被推进并 save（UPDATE 路径——id 非空）
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("400.00", ar.getSettledAmount());
        assertSame(ar, receivableRepo.lastSaved, "应保存被核销的同一聚合");

        // 落了一条核销记录，字段逐一断言（等价 ArgumentCaptor）
        SettlementRecord saved = settlementRepo.lastSaved;
        assertSame(rec, saved, "返回的就是落库那条");
        assertEquals(SettlementType.RECEIVABLE, saved.getType());
        assertEquals(10L, saved.getTargetId());
        assertEquals("SINV-1", saved.getTargetSourceDocNo());
        assertEqualsDecimal("400.00", saved.getAmount());
        assertEquals(DATE, saved.getSettlementDate());
        assertEquals("PAY-1", saved.getPaymentDocNo());
        assertEquals(OPERATOR, saved.getCreatedBy());
        assertNotNull(saved.getId(), "落库后应回填 id");

        // 顺序：先 save 子账，再 save 记录
        assertTrue(receivableRepo.saveSeq < settlementRepo.saveSeq,
                "应先保存子账再落核销记录");
    }

    @Test
    void 应收全额核销_状态SETTLED() {
        AccountsReceivable ar = AccountsReceivable.restore(11L, 7L, new BigDecimal("500.00"),
                new BigDecimal("0.00"), "SINV-2", DUE, ReceivableStatus.OPEN, "creator");
        receivableRepo.seed(ar);

        service.settleReceivable(11L, new BigDecimal("500.00"), DATE, null, OPERATOR);

        assertEquals(ReceivableStatus.SETTLED, ar.getStatus());
        assertEqualsDecimal("500.00", ar.getSettledAmount());
        // paymentDocNo 传 null（T03 形态）应原样落记录
        org.junit.jupiter.api.Assertions.assertNull(settlementRepo.lastSaved.getPaymentDocNo());
    }

    @Test
    void 应收不存在_抛ReceivableNotFound_不落记录() {
        assertThrows(ReceivableNotFoundException.class,
                () -> service.settleReceivable(999L, new BigDecimal("1.00"), DATE, null, OPERATOR));
        assertEquals(0, settlementRepo.store.size(), "未装载到子账不应落核销记录");
    }

    @Test
    void 应收超额核销_抛OverSettlement_不落记录() {
        AccountsReceivable ar = AccountsReceivable.restore(12L, 7L, new BigDecimal("100.00"),
                new BigDecimal("0.00"), "SINV-3", DUE, ReceivableStatus.OPEN, "creator");
        receivableRepo.seed(ar);

        assertThrows(OverSettlementException.class,
                () -> service.settleReceivable(12L, new BigDecimal("100.01"), DATE, null, OPERATOR));
        assertEquals(0, settlementRepo.store.size(), "超额被子账拒绝，不应落核销记录");
        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
    }

    // ---------------- 应付核销 ----------------

    @Test
    void 应付核销_装载_settle_save_落记录_字段正确() {
        AccountsPayable ap = AccountsPayable.restore(20L, 3L, new BigDecimal("800.00"),
                "PINV-1", DUE, PayableStatus.OPEN, new BigDecimal("0.00"), "creator", Instant.now());
        payableRepo.seed(ap);

        SettlementRecord rec = service.settlePayable(20L, new BigDecimal("800.00"),
                DATE, "PAY-9", OPERATOR);

        assertEquals(PayableStatus.SETTLED, ap.getStatus());
        assertEqualsDecimal("800.00", ap.getSettledAmount());
        assertSame(ap, payableRepo.lastSaved);

        SettlementRecord saved = settlementRepo.lastSaved;
        assertSame(rec, saved);
        assertEquals(SettlementType.PAYABLE, saved.getType());
        assertEquals(20L, saved.getTargetId());
        assertEquals("PINV-1", saved.getTargetSourceDocNo());
        assertEqualsDecimal("800.00", saved.getAmount());
        assertEquals(DATE, saved.getSettlementDate());
        assertEquals("PAY-9", saved.getPaymentDocNo());
        assertEquals(OPERATOR, saved.getCreatedBy());
        assertTrue(payableRepo.saveSeq < settlementRepo.saveSeq, "应先保存子账再落核销记录");
    }

    @Test
    void 应付不存在_抛PayableNotFound_不落记录() {
        assertThrows(PayableNotFoundException.class,
                () -> service.settlePayable(999L, new BigDecimal("1.00"), DATE, null, OPERATOR));
        assertEquals(0, settlementRepo.store.size());
    }

    @Test
    void 应付超额核销_抛OverSettlement_不落记录() {
        AccountsPayable ap = AccountsPayable.restore(21L, 3L, new BigDecimal("100.00"),
                "PINV-2", DUE, PayableStatus.OPEN, new BigDecimal("0.00"), "creator", Instant.now());
        payableRepo.seed(ap);
        assertThrows(OverSettlementException.class,
                () -> service.settlePayable(21L, new BigDecimal("100.01"), DATE, null, OPERATOR));
        assertEquals(0, settlementRepo.store.size());
        assertEquals(PayableStatus.OPEN, ap.getStatus());
    }

    // ---------------- 入参校验 ----------------

    @Test
    void operator为空_应收应付均拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.settleReceivable(1L, new BigDecimal("1"), DATE, null, " "));
        assertThrows(IllegalArgumentException.class,
                () -> service.settleReceivable(1L, new BigDecimal("1"), DATE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.settlePayable(1L, new BigDecimal("1"), DATE, null, " "));
    }

    @Test
    void 核销业务日为空_应收应付均拒绝() {
        assertThrows(NullPointerException.class,
                () -> service.settleReceivable(1L, new BigDecimal("1"), null, null, OPERATOR));
        assertThrows(NullPointerException.class,
                () -> service.settlePayable(1L, new BigDecimal("1"), null, null, OPERATOR));
    }

    @Test
    void 空校验先于装载_不触发NotFound() {
        // operator 空应在 findById 之前被拒（不存在的 id 也不抛 NotFound）
        assertThrows(IllegalArgumentException.class,
                () -> service.settleReceivable(999L, new BigDecimal("1"), DATE, null, null));
        assertEquals(0, receivableRepo.findByIdCalls, "校验失败不应装载子账");
    }

    // ---------------- 只读委派 ----------------

    @Test
    void 读应收核销历史_委派findByTarget() {
        SettlementRecord r1 = SettlementRecord.record(SettlementType.RECEIVABLE, 10L, "SINV-1",
                new BigDecimal("100.00"), DATE, null, OPERATOR);
        settlementRepo.save(r1);
        List<SettlementRecord> out = service.findReceivableSettlements(10L);
        assertEquals(1, out.size());
        assertEquals(SettlementType.RECEIVABLE, settlementRepo.lastFindType);
        assertEquals(10L, settlementRepo.lastFindTargetId);
    }

    @Test
    void 读应付核销历史_委派findByTarget() {
        service.findPayableSettlements(20L);
        assertEquals(SettlementType.PAYABLE, settlementRepo.lastFindType);
        assertEquals(20L, settlementRepo.lastFindTargetId);
    }

    // ---------------- 审计 ----------------

    // ---------------- 反向核销（M4-T07c unsettle，落负额记录） ----------------

    @Test
    void 反向核销应收_装载_unsettle_save_落负额记录_字段正确() {
        AccountsReceivable ar = AccountsReceivable.restore(30L, 7L, new BigDecimal("1000.00"),
                new BigDecimal("1000.00"), "SINV-9", DUE, ReceivableStatus.SETTLED, "creator");
        receivableRepo.seed(ar);

        SettlementRecord rec = service.unsettleReceivable(30L, new BigDecimal("400.00"),
                DATE, "RCPT-1", OPERATOR);

        // 子账已核销额回退、状态回 PARTIAL，并 save（UPDATE）
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("600.00", ar.getSettledAmount());
        assertSame(ar, receivableRepo.lastSaved, "应保存被反向的同一聚合");

        // 落了一条负额反向核销记录（amount = -400.00）
        SettlementRecord saved = settlementRepo.lastSaved;
        assertSame(rec, saved, "返回的就是落库那条");
        assertEquals(SettlementType.RECEIVABLE, saved.getType());
        assertEquals(30L, saved.getTargetId());
        assertEquals("SINV-9", saved.getTargetSourceDocNo());
        assertEqualsDecimal("-400.00", saved.getAmount());
        assertTrue(saved.getAmount().signum() < 0, "反向记录金额必须为负");
        assertEquals(DATE, saved.getSettlementDate());
        assertEquals("RCPT-1", saved.getPaymentDocNo(), "反查锚点=被冲销收款单号");
        assertEquals(OPERATOR, saved.getCreatedBy());
        assertNotNull(saved.getId(), "落库后应回填 id");

        // 顺序：先 save 子账，再落反向记录
        assertTrue(receivableRepo.saveSeq < settlementRepo.saveSeq,
                "应先保存子账再落反向核销记录");
    }

    @Test
    void 反向核销应收_全额回退_状态回OPEN() {
        AccountsReceivable ar = AccountsReceivable.restore(31L, 7L, new BigDecimal("500.00"),
                new BigDecimal("500.00"), "SINV-10", DUE, ReceivableStatus.SETTLED, "creator");
        receivableRepo.seed(ar);

        service.unsettleReceivable(31L, new BigDecimal("500.00"), DATE, "RCPT-2", OPERATOR);

        assertEquals(ReceivableStatus.OPEN, ar.getStatus());
        assertEqualsDecimal("0", ar.getSettledAmount());
        assertEqualsDecimal("-500.00", settlementRepo.lastSaved.getAmount());
    }

    @Test
    void 反向核销应收_下溢_抛异常_不落记录() {
        AccountsReceivable ar = AccountsReceivable.restore(32L, 7L, new BigDecimal("1000.00"),
                new BigDecimal("300.00"), "SINV-11", DUE, ReceivableStatus.PARTIAL, "creator");
        receivableRepo.seed(ar);

        assertThrows(IllegalArgumentException.class,
                () -> service.unsettleReceivable(32L, new BigDecimal("300.01"), DATE, "RCPT-3", OPERATOR));
        assertEquals(0, settlementRepo.store.size(), "下溢被子账拒绝，不应落反向记录");
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
        assertEqualsDecimal("300.00", ar.getSettledAmount());
    }

    @Test
    void 反向核销应收_不存在_抛ReceivableNotFound_不落记录() {
        assertThrows(ReceivableNotFoundException.class,
                () -> service.unsettleReceivable(999L, new BigDecimal("1.00"), DATE, "RCPT-X", OPERATOR));
        assertEquals(0, settlementRepo.store.size());
    }

    @Test
    void 反向核销应付_装载_unsettle_save_落负额记录_字段正确() {
        AccountsPayable ap = AccountsPayable.restore(40L, 3L, new BigDecimal("800.00"),
                "PINV-9", DUE, PayableStatus.SETTLED, new BigDecimal("800.00"), "creator", Instant.now());
        payableRepo.seed(ap);

        SettlementRecord rec = service.unsettlePayable(40L, new BigDecimal("800.00"),
                DATE, "PAYV-9", OPERATOR);

        assertEquals(PayableStatus.OPEN, ap.getStatus());
        assertEqualsDecimal("0", ap.getSettledAmount());
        assertSame(ap, payableRepo.lastSaved);

        SettlementRecord saved = settlementRepo.lastSaved;
        assertSame(rec, saved);
        assertEquals(SettlementType.PAYABLE, saved.getType());
        assertEquals(40L, saved.getTargetId());
        assertEquals("PINV-9", saved.getTargetSourceDocNo());
        assertEqualsDecimal("-800.00", saved.getAmount());
        assertEquals("PAYV-9", saved.getPaymentDocNo());
        assertTrue(payableRepo.saveSeq < settlementRepo.saveSeq, "应先保存子账再落反向核销记录");
    }

    @Test
    void 反向核销应付_部分回退_状态PARTIAL() {
        AccountsPayable ap = AccountsPayable.restore(41L, 3L, new BigDecimal("800.00"),
                "PINV-10", DUE, PayableStatus.SETTLED, new BigDecimal("800.00"), "creator", Instant.now());
        payableRepo.seed(ap);

        service.unsettlePayable(41L, new BigDecimal("300.00"), DATE, "PAYV-10", OPERATOR);

        assertEquals(PayableStatus.PARTIAL, ap.getStatus());
        assertEqualsDecimal("500.00", ap.getSettledAmount());
        assertEqualsDecimal("-300.00", settlementRepo.lastSaved.getAmount());
    }

    @Test
    void 反向核销应付_不存在_抛PayableNotFound_不落记录() {
        assertThrows(PayableNotFoundException.class,
                () -> service.unsettlePayable(999L, new BigDecimal("1.00"), DATE, "PAYV-X", OPERATOR));
        assertEquals(0, settlementRepo.store.size());
    }

    @Test
    void 反向核销_operator与业务日空校验() {
        assertThrows(IllegalArgumentException.class,
                () -> service.unsettleReceivable(1L, new BigDecimal("1"), DATE, "RCPT-1", " "));
        assertThrows(NullPointerException.class,
                () -> service.unsettlePayable(1L, new BigDecimal("1"), null, "PAYV-1", OPERATOR));
        assertEquals(0, receivableRepo.findByIdCalls, "校验失败不应装载子账");
    }

    @Test
    void Σ核销记录含负额恒等于settled_不变式() {
        // settle 1000 后 unsettle 400：核销记录 Σ = 1000 + (-400) = 600 == 子账 settled
        AccountsReceivable ar = AccountsReceivable.restore(50L, 7L, new BigDecimal("1000.00"),
                new BigDecimal("0.00"), "SINV-50", DUE, ReceivableStatus.OPEN, "creator");
        receivableRepo.seed(ar);

        service.settleReceivable(50L, new BigDecimal("1000.00"), DATE, "RCPT-50", OPERATOR);
        service.unsettleReceivable(50L, new BigDecimal("400.00"), DATE, "RCPT-50", OPERATOR);

        java.math.BigDecimal sum = settlementRepo.store.stream()
                .filter(r -> r.getType() == SettlementType.RECEIVABLE && r.getTargetId() == 50L)
                .map(SettlementRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(ar.getSettledAmount()),
                "Σ核销记录（含负额）应恒等于子账 settledAmount；实际 Σ=" + sum.toPlainString()
                        + " settled=" + ar.getSettledAmount().toPlainString());
        assertEqualsDecimal("600.00", ar.getSettledAmount());
        assertEquals(ReceivableStatus.PARTIAL, ar.getStatus());
    }

    @Test
    void 反向写方法标注Audited_targetType为settlement() throws NoSuchMethodException {
        Method unsettleAr = SettlementService.class.getMethod("unsettleReceivable", long.class,
                BigDecimal.class, LocalDate.class, String.class, String.class);
        Method unsettleAp = SettlementService.class.getMethod("unsettlePayable", long.class,
                BigDecimal.class, LocalDate.class, String.class, String.class);
        Audited a1 = unsettleAr.getAnnotation(Audited.class);
        Audited a2 = unsettleAp.getAnnotation(Audited.class);
        assertNotNull(a1, "unsettleReceivable 应标 @Audited");
        assertNotNull(a2, "unsettlePayable 应标 @Audited");
        assertEquals("settlement", a1.targetType());
        assertEquals("settlement", a2.targetType());
        assertEquals("settlement.unsettle.receivable", a1.action());
        assertEquals("settlement.unsettle.payable", a2.action());
    }

    @Test
    void 写方法标注Audited_targetType为settlement() throws NoSuchMethodException {
        Method settleAr = SettlementService.class.getMethod("settleReceivable", long.class,
                BigDecimal.class, LocalDate.class, String.class, String.class);
        Method settleAp = SettlementService.class.getMethod("settlePayable", long.class,
                BigDecimal.class, LocalDate.class, String.class, String.class);

        Audited a1 = settleAr.getAnnotation(Audited.class);
        Audited a2 = settleAp.getAnnotation(Audited.class);
        assertNotNull(a1, "settleReceivable 应标 @Audited");
        assertNotNull(a2, "settlePayable 应标 @Audited");
        assertEquals("settlement", a1.targetType());
        assertEquals("settlement", a2.targetType());
        assertEquals("settlement.receivable", a1.action());
        assertEquals("settlement.payable", a2.action());
    }

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    // ============== 手写内存替身仓储（记录最后一次 save 与调用次序） ==============

    private static final class FakeReceivableRepository implements ReceivableRepository {
        final Map<Long, AccountsReceivable> store = new HashMap<>();
        AccountsReceivable lastSaved;
        long saveSeq = -1;
        int findByIdCalls;

        void seed(AccountsReceivable ar) {
            store.put(ar.getId(), ar);
        }

        @Override
        public void save(AccountsReceivable receivable) {
            lastSaved = receivable;
            saveSeq = SaveClock.next();
            store.put(receivable.getId(), receivable);
        }

        @Override
        public List<AccountsReceivable> findBySourceDocNo(String sourceDocNo) {
            return new ArrayList<>();
        }

        @Override
        public Optional<AccountsReceivable> findById(long id) {
            findByIdCalls++;
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public PageResult<AccountsReceivable> search(ReceivableQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(), 1, 20);
        }
    }

    private static final class FakePayableRepository implements AccountsPayableRepository {
        final Map<Long, AccountsPayable> store = new HashMap<>();
        AccountsPayable lastSaved;
        long saveSeq = -1;

        void seed(AccountsPayable ap) {
            store.put(ap.getId(), ap);
        }

        @Override
        public void save(AccountsPayable payable) {
            lastSaved = payable;
            saveSeq = SaveClock.next();
            store.put(payable.getId(), payable);
        }

        @Override
        public List<AccountsPayable> findBySourceDocNo(String sourceDocNo) {
            return new ArrayList<>();
        }

        @Override
        public Optional<AccountsPayable> findById(long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public PageResult<AccountsPayable> search(AccountsPayableQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(), 1, 20);
        }
    }

    private static final class FakeSettlementRecordRepository implements SettlementRecordRepository {
        final List<SettlementRecord> store = new ArrayList<>();
        final AtomicLong idGen = new AtomicLong();
        SettlementRecord lastSaved;
        long saveSeq = -1;
        SettlementType lastFindType;
        long lastFindTargetId = -1;

        @Override
        public void save(SettlementRecord record) {
            if (record.getId() == null) {
                record.assignId(idGen.incrementAndGet());
            }
            lastSaved = record;
            saveSeq = SaveClock.next();
            store.add(record);
        }

        @Override
        public List<SettlementRecord> findByTarget(SettlementType type, long targetId) {
            lastFindType = type;
            lastFindTargetId = targetId;
            List<SettlementRecord> out = new ArrayList<>();
            for (SettlementRecord r : store) {
                if (r.getType() == type && r.getTargetId() == targetId) {
                    out.add(r);
                }
            }
            out.sort(Comparator.comparing(SettlementRecord::getId));
            return out;
        }

        @Override
        public List<SettlementRecord> findByPaymentDocNo(String paymentDocNo) {
            return new ArrayList<>();
        }
    }

    /** 单调递增计数器：用于断言「先 save 子账后落核销记录」的调用次序 */
    private static final class SaveClock {
        private static final AtomicLong CLOCK = new AtomicLong();
        static long next() {
            return CLOCK.incrementAndGet();
        }
    }
}
