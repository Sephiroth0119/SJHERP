package com.sjherp.domain.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 付款单领域单测（M4-T04b，对称收款单）：覆盖付款单聚合 {@link PaymentDisbursement} /
 * 行 {@link PaymentDisbursementLine} 的建单校验与金额求和精度，以及领域服务
 * {@link PaymentDisbursementService} 的状态机推进（create→approve→post）与<b>职责边界</b>
 * （post 仅推单据状态机至 COMPLETED，不碰应付子账 / 核销 / GL——跨聚合编排在 app 层）。
 *
 * <p>用内存替身仓储 {@link FakePaymentDisbursementRepository} + 无操作事件发布器；
 * 不连真库、不依赖 Spring，照 {@code PurchaseInvoiceServiceTest} 范式。
 */
class PaymentDisbursementServiceTest {

    private static final long SUPPLIER = 1L;
    private static final long ACCOUNT = 10L;
    private static final long PAYABLE_1 = 100L;
    private static final long PAYABLE_2 = 200L;
    private static final LocalDate D = LocalDate.of(2026, 6, 14);
    private static final String OPERATOR = "tester";

    private FakePaymentDisbursementRepository repository;
    private PaymentDisbursementService service;

    @BeforeEach
    void setUp() {
        repository = new FakePaymentDisbursementRepository();
        service = new PaymentDisbursementService(repository, NoopPublisher.INSTANCE);
    }

    // ===================================================== 聚合 / 行 建单校验

    @Test
    void 建单为草稿_总额为各行分摊金额之和_2位精度() {
        PaymentDisbursement d = service.create("PAYV-1", SUPPLIER, ACCOUNT, D, "付货款",
                List.of(lineInput(PAYABLE_1, "300.00"), lineInput(PAYABLE_2, "150.50")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, d.getStatus());
        assertEquals(SUPPLIER, d.getSupplierId());
        assertEquals(ACCOUNT, d.getPaymentAccountId());
        assertEquals(D, d.getPaymentDate());
        assertEquals(2, d.getLines().size());
        // 行号由领域服务从 1 起自动编（顺序）
        assertEquals(1, d.getLines().get(0).getLineNo());
        assertEquals(2, d.getLines().get(1).getLineNo());
        assertEqualsDecimal("450.50", d.totalAmount());
    }

    @Test
    void 总额求和保留2位小数() {
        // 0.1 + 0.2 = 0.30（2 位），不出现 0.30000000000000004 浮点误差
        PaymentDisbursement d = service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null,
                List.of(lineInput(PAYABLE_1, "0.10"), lineInput(PAYABLE_2, "0.20")), OPERATOR);
        assertEquals(2, d.totalAmount().scale());
        assertEqualsDecimal("0.30", d.totalAmount());
    }

    @Test
    void 建单至少一行_空行集合拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null, List.of(), OPERATOR));
        assertTrue(ex.getMessage().contains("至少要有一行"), ex.getMessage());
    }

    @Test
    void 聚合工厂直接建单_行号重复拒绝() {
        // 直绕领域服务自动编号，构造行号冲突的聚合 → PaymentDisbursement.create 行号唯一校验
        List<PaymentDisbursementLine> dup = List.of(
                PaymentDisbursementLine.create(1, PAYABLE_1, new BigDecimal("100.00")),
                PaymentDisbursementLine.create(1, PAYABLE_2, new BigDecimal("50.00")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PaymentDisbursement.create("PAYV-1", SUPPLIER, ACCOUNT, D, null, dup, OPERATOR));
        assertTrue(ex.getMessage().contains("行号不能重复"), ex.getMessage());
    }

    @Test
    void 聚合工厂_同一应付在多行重复分摊拒绝() {
        // 两行行号不同但引用同一 payableId → 单内重复分摊，必须拒绝
        List<PaymentDisbursementLine> dupPayable = List.of(
                PaymentDisbursementLine.create(1, PAYABLE_1, new BigDecimal("100.00")),
                PaymentDisbursementLine.create(2, PAYABLE_1, new BigDecimal("50.00")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PaymentDisbursement.create("PAYV-1", SUPPLIER, ACCOUNT, D, null, dupPayable, OPERATOR));
        assertTrue(ex.getMessage().contains("重复分摊"), ex.getMessage());
    }

    @Test
    void 聚合工厂直接建单_空行集合拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PaymentDisbursement.create("PAYV-1", SUPPLIER, ACCOUNT, D, null, List.of(), OPERATOR));
        assertTrue(ex.getMessage().contains("至少要有一行"), ex.getMessage());
    }

    @Test
    void 行金额必须大于0() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PaymentDisbursementLine.create(1, PAYABLE_1, new BigDecimal("0.00")));
        assertTrue(ex.getMessage().contains("大于 0"), ex.getMessage());
    }

    @Test
    void 行金额负数拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> PaymentDisbursementLine.create(1, PAYABLE_1, new BigDecimal("-1")));
    }

    @Test
    void 行金额超2位小数拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PaymentDisbursementLine.create(1, PAYABLE_1, new BigDecimal("1.234")));
        assertTrue(ex.getMessage().contains("2 位小数"), ex.getMessage());
    }

    @Test
    void 行号小于1拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PaymentDisbursementLine.create(0, PAYABLE_1, new BigDecimal("1.00")));
        assertTrue(ex.getMessage().contains("行号必须 >= 1"), ex.getMessage());
    }

    // ===================================================== 状态机推进

    @Test
    void 审核_草稿到已审核() {
        service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null,
                List.of(lineInput(PAYABLE_1, "100.00")), OPERATOR);
        PaymentDisbursement approved = service.approve("PAYV-1", OPERATOR);
        assertEquals(DocumentStatus.APPROVED, approved.getStatus());
    }

    @Test
    void 过账_已审核一步推进到已完成() {
        service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null,
                List.of(lineInput(PAYABLE_1, "100.00")), OPERATOR);
        service.approve("PAYV-1", OPERATOR);
        PaymentDisbursement posted = service.post("PAYV-1", OPERATOR);
        // post 内部 startExecution(APPROVED→EXECUTING) + complete(EXECUTING→COMPLETED) 一步过
        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
    }

    @Test
    void 未审核直接过账_非法流转拒绝() {
        service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null,
                List.of(lineInput(PAYABLE_1, "100.00")), OPERATOR);
        // DRAFT 直接 post → startExecution 要求 APPROVED → IllegalStateTransitionException
        assertThrows(IllegalStateTransitionException.class, () -> service.post("PAYV-1", OPERATOR));
        // 状态仍为草稿（流转被拒，模型未破坏）
        assertEquals(DocumentStatus.DRAFT, service.get("PAYV-1").getStatus());
    }

    @Test
    void 重复过账_已完成单再过账非法流转拒绝() {
        service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null,
                List.of(lineInput(PAYABLE_1, "100.00")), OPERATOR);
        service.approve("PAYV-1", OPERATOR);
        service.post("PAYV-1", OPERATOR);
        // COMPLETED 只能 REVERSED；再 post（→EXECUTING）非法 → 单据状态机即幂等主防线
        assertThrows(IllegalStateTransitionException.class, () -> service.post("PAYV-1", OPERATOR));
        assertEquals(DocumentStatus.COMPLETED, service.get("PAYV-1").getStatus());
    }

    @Test
    void 重复审核_已审核单再审核非法流转拒绝() {
        service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null,
                List.of(lineInput(PAYABLE_1, "100.00")), OPERATOR);
        service.approve("PAYV-1", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.approve("PAYV-1", OPERATOR));
    }

    @Test
    void 各写方法operator为空拒绝() {
        assertThrows(IllegalArgumentException.class, () -> service.create("PAYV-1", SUPPLIER, ACCOUNT, D,
                null, List.of(lineInput(PAYABLE_1, "100.00")), "  "));
    }

    @Test
    void 查询不存在的付款单抛NotFound() {
        assertThrows(PaymentDisbursementNotFoundException.class, () -> service.get("PAYV-NONE"));
    }

    @Test
    void post仅推单据状态机_不产生任何额外副作用() {
        // 职责边界断言：领域 post 只改单据本身状态并保存一次，仓储里只有这一张单（无核销/凭证产物）
        service.create("PAYV-1", SUPPLIER, ACCOUNT, D, null,
                List.of(lineInput(PAYABLE_1, "100.00"), lineInput(PAYABLE_2, "50.00")), OPERATOR);
        service.approve("PAYV-1", OPERATOR);
        PaymentDisbursement posted = service.post("PAYV-1", OPERATOR);
        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
        // 仓储仅含该付款单（领域层不触达应付/核销/GL，无跨聚合写入）
        assertEquals(1, repository.store.size());
    }

    // ===================================================== 工具

    private static void assertEqualsDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + (actual == null ? "null" : actual.toPlainString()));
    }

    private static PaymentDisbursementLineInput lineInput(long payableId, String amount) {
        return new PaymentDisbursementLineInput(payableId, new BigDecimal(amount));
    }

    private enum NoopPublisher implements DomainEventPublisher {
        INSTANCE;

        @Override
        public void publish(DomainEvent event) {
            // no-op
        }
    }

    private static final class FakePaymentDisbursementRepository implements PaymentDisbursementRepository {

        private final Map<String, PaymentDisbursement> store = new HashMap<>();
        private final AtomicLong lineIdSeq = new AtomicLong();

        @Override
        public void save(PaymentDisbursement disbursement) {
            // 模拟落库回填行 id（首次保存且未回填时），照真实仓储语义
            for (PaymentDisbursementLine line : disbursement.getLines()) {
                if (line.getId() == null) {
                    line.assignId(lineIdSeq.incrementAndGet());
                }
            }
            store.put(disbursement.getDocNo(), disbursement);
        }

        @Override
        public Optional<PaymentDisbursement> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<PaymentDisbursement> search(PaymentDisbursementQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }
}
