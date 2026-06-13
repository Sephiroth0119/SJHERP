package com.sjherp.domain.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.audit.Audited;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;

/**
 * 收款单领域单测（M4-T04b）：聚合工厂校验（≥1 行 / 行号唯一 / 各行金额 > 0 与 2 位精度）、
 * totalAmount 求和与精度、领域服务状态机（create→approve→post 三步推进 DRAFT→APPROVED→COMPLETED，
 * 非法流转抛 IllegalStateTransitionException）、operator 校验、@Audited 标注存在性。
 *
 * <p>不连真库：用内存替身仓储 + NoopPublisher，照 {@code PurchaseInvoiceServiceTest} / {@code ReceivableServiceTest} 风格。
 * 跨聚合编排（核销 + 现金侧凭证）不在本服务内——见 {@code CollectionReceiptAppServiceTest}。
 */
class CollectionReceiptServiceTest {

    private static final long CUSTOMER = 7L;
    private static final long PAYMENT_ACCOUNT = 3L;
    private static final long RECEIVABLE_A = 100L;
    private static final long RECEIVABLE_B = 200L;
    private static final LocalDate D = LocalDate.of(2026, 6, 14);
    private static final String OPERATOR = "tester";

    private FakeCollectionReceiptRepository repository;
    private CollectionReceiptService service;

    @BeforeEach
    void setUp() {
        repository = new FakeCollectionReceiptRepository();
        service = new CollectionReceiptService(repository, NoopPublisher.INSTANCE);
    }

    // ----------------------------------------------------- 聚合工厂：建单校验

    @Test
    void 建单为草稿_行号自增_总额等于各行之和() {
        CollectionReceipt receipt = service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, "回款",
                List.of(line(RECEIVABLE_A, "300.00"), line(RECEIVABLE_B, "200.00")), OPERATOR);

        assertEquals(DocumentStatus.DRAFT, receipt.getStatus());
        assertEquals(CUSTOMER, receipt.getCustomerId());
        assertEquals(PAYMENT_ACCOUNT, receipt.getPaymentAccountId());
        assertEquals(D, receipt.getReceiptDate());
        assertEquals("回款", receipt.getRemark());
        assertEquals(2, receipt.getLines().size());
        // 行号由领域服务从 1 起自增
        assertEquals(1, receipt.getLines().get(0).getLineNo());
        assertEquals(2, receipt.getLines().get(1).getLineNo());
        assertEquals(RECEIVABLE_A, receipt.getLines().get(0).getReceivableId());
        assertEqualsDecimal("500.00", receipt.totalAmount());
    }

    @Test
    void 建单至少一行_空行集合拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null, List.of(), OPERATOR));
        assertTrue(ex.getMessage().contains("至少要有一行"), ex.getMessage());
    }

    @Test
    void 建单行集合为null拒绝() {
        assertThrows(NullPointerException.class,
                () -> service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null, null, OPERATOR));
    }

    @Test
    void 各行分摊金额必须大于0() {
        // 0 元分摊行 → CollectionReceiptLine.create 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                        List.of(line(RECEIVABLE_A, "0")), OPERATOR));
        // 负数分摊行 → 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                        List.of(line(RECEIVABLE_A, "-1")), OPERATOR));
    }

    @Test
    void 各行分摊金额超2位小数拒绝() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                        List.of(line(RECEIVABLE_A, "100.123")), OPERATOR));
        assertTrue(ex.getMessage().contains("小数"), ex.getMessage());
    }

    @Test
    void operator为空拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                        List.of(line(RECEIVABLE_A, "100.00")), "  "));
    }

    @Test
    void 收款日期为空拒绝() {
        assertThrows(NullPointerException.class,
                () -> service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, null, null,
                        List.of(line(RECEIVABLE_A, "100.00")), OPERATOR));
    }

    // ----------------------------------------------------- 聚合不变量：行号唯一 / totalAmount 精度

    @Test
    void 工厂直接构造_行号重复拒绝() {
        // 领域服务的 create 行号自增不会撞号，但聚合工厂自身必须守门（持久层 restore 之外的入口）
        List<CollectionReceiptLine> dup = List.of(
                CollectionReceiptLine.create(1, RECEIVABLE_A, new BigDecimal("100.00")),
                CollectionReceiptLine.create(1, RECEIVABLE_B, new BigDecimal("200.00")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CollectionReceipt.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null, dup, OPERATOR));
        assertTrue(ex.getMessage().contains("行号不能重复"), ex.getMessage());
    }

    @Test
    void 聚合工厂_同一应收在多行重复分摊拒绝() {
        // 两行行号不同但引用同一 receivableId → 单内重复分摊，必须拒绝
        List<CollectionReceiptLine> dupReceivable = List.of(
                CollectionReceiptLine.create(1, RECEIVABLE_A, new BigDecimal("100.00")),
                CollectionReceiptLine.create(2, RECEIVABLE_A, new BigDecimal("200.00")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CollectionReceipt.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                        dupReceivable, OPERATOR));
        assertTrue(ex.getMessage().contains("重复分摊"), ex.getMessage());
    }

    @Test
    void totalAmount求和并归一为2位小数() {
        CollectionReceipt receipt = service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "0.10"), line(RECEIVABLE_B, "0.20")), OPERATOR);
        BigDecimal total = receipt.totalAmount();
        assertEqualsDecimal("0.30", total);
        // 精度契约：2 位小数标度
        assertEquals(2, total.scale());
    }

    @Test
    void 单行总额等于该行金额() {
        CollectionReceipt receipt = service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "999.99")), OPERATOR);
        assertEqualsDecimal("999.99", receipt.totalAmount());
    }

    // ----------------------------------------------------- 状态机：create → approve → post

    @Test
    void 审核_草稿到已审核() {
        service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        CollectionReceipt approved = service.approve("RCPT-1", OPERATOR);
        assertEquals(DocumentStatus.APPROVED, approved.getStatus());
    }

    @Test
    void 过账_已审核经执行中到完成() {
        service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        service.approve("RCPT-1", OPERATOR);
        CollectionReceipt posted = service.post("RCPT-1", OPERATOR);
        // post 内部 startExecution → complete，终态 COMPLETED
        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
    }

    @Test
    void 草稿直接过账非法流转被拒() {
        service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        // 未审核（DRAFT）直接 post：startExecution 要求 APPROVED → 非法流转
        assertThrows(IllegalStateTransitionException.class, () -> service.post("RCPT-1", OPERATOR));
        // 状态未被破坏，仍为 DRAFT
        assertEquals(DocumentStatus.DRAFT, service.get("RCPT-1").getStatus());
    }

    @Test
    void 重复审核非法流转被拒() {
        service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        service.approve("RCPT-1", OPERATOR);
        assertThrows(IllegalStateTransitionException.class, () -> service.approve("RCPT-1", OPERATOR));
    }

    @Test
    void 已完成单据再过账非法流转被拒() {
        service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        service.approve("RCPT-1", OPERATOR);
        service.post("RCPT-1", OPERATOR);
        // COMPLETED 再 post → 幂等防线：非法流转，子账/凭证不会二次生效（编排层）
        assertThrows(IllegalStateTransitionException.class, () -> service.post("RCPT-1", OPERATOR));
    }

    @Test
    void post仅推进状态机_不触碰核销与凭证() {
        // 本服务无核销/凭证依赖（构造器仅 repo + publisher），post 通过即证明只推状态机
        service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        service.approve("RCPT-1", OPERATOR);
        CollectionReceipt posted = service.post("RCPT-1", OPERATOR);
        assertEquals(DocumentStatus.COMPLETED, posted.getStatus());
        // 落库为最终状态
        assertEquals(DocumentStatus.COMPLETED, repository.store.get("RCPT-1").getStatus());
    }

    // ----------------------------------------------------- 查询

    @Test
    void 查询不存在的收款单抛NotFound() {
        assertThrows(CollectionReceiptNotFoundException.class, () -> service.get("RCPT-NONE"));
    }

    @Test
    void 审核不存在的收款单抛NotFound() {
        assertThrows(CollectionReceiptNotFoundException.class, () -> service.approve("RCPT-NONE", OPERATOR));
    }

    @Test
    void 分页查询返回仓储结果() {
        service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        PageResult<CollectionReceipt> page = service.search(
                new CollectionReceiptQuery(CUSTOMER, null, null, 1, 20));
        assertEquals(1, page.items().size());
    }

    // ----------------------------------------------------- @Audited 标注存在性（可审计原则 3）

    @Test
    void 写方法均标注Audited() throws NoSuchMethodException {
        assertAudited("create", String.class, long.class, long.class, LocalDate.class, String.class,
                List.class, String.class);
        assertAudited("approve", String.class, String.class);
        assertAudited("post", String.class, String.class);
    }

    // ----------------------------------------------------- 审计目标摘要

    @Test
    void auditTarget暴露单号与摘要() {
        CollectionReceipt receipt = service.create("RCPT-1", CUSTOMER, PAYMENT_ACCOUNT, D, null,
                List.of(line(RECEIVABLE_A, "100.00")), OPERATOR);
        assertEquals("RCPT-1", receipt.auditTargetCode());
        String summary = receipt.auditSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("100.00"), summary);
        assertTrue(summary.contains("行数=1"), summary);
    }

    // ----------------------------------------------------- 工具

    private static CollectionReceiptLineInput line(long receivableId, String amount) {
        return new CollectionReceiptLineInput(receivableId, new BigDecimal(amount));
    }

    private static void assertAudited(String method, Class<?>... params) throws NoSuchMethodException {
        Audited audited = CollectionReceiptService.class.getDeclaredMethod(method, params)
                .getAnnotation(Audited.class);
        assertNotNull(audited, method + " 应标注 @Audited");
        assertEquals("collection_receipt", audited.targetType());
        assertTrue(audited.action().startsWith("collection_receipt."), audited.action());
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

    private static final class FakeCollectionReceiptRepository implements CollectionReceiptRepository {

        private final Map<String, CollectionReceipt> store = new HashMap<>();

        @Override
        public void save(CollectionReceipt receipt) {
            store.put(receipt.getDocNo(), receipt);
        }

        @Override
        public Optional<CollectionReceipt> findByDocNo(String docNo) {
            return Optional.ofNullable(store.get(docNo));
        }

        @Override
        public PageResult<CollectionReceipt> search(CollectionReceiptQuery query) {
            return new PageResult<>(new ArrayList<>(store.values()), store.size(),
                    query.page(), query.size());
        }
    }
}
