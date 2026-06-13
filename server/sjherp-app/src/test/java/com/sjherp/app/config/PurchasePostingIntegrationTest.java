package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.purchase.PurchaseInvoiceLineInput;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptService;

/**
 * 采购线过账整链集成测试（M3-T05/T06/T07 验收核心，Testcontainers 真实 MySQL）：
 * 用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link PurchaseInfraConfig}）跑通：
 * <ul>
 *   <li>下单（PO）→ 审核 → 部分收货（PR）过账 → 断言库存增加 + PURCHASE_IN 流水 + PO 行 received_qty 回写；</li>
 *   <li>开发票（PINV）→ 审核 → 过账 → 断言生成应付（金额 = 发票总额、状态 OPEN、月结到期日 = 发票日 +1 月）；</li>
 *   <li>对账恒等式：Σ库存流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额。</li>
 * </ul>
 *
 * <p>不直接走 app 的 AppService（避免装配 catalog/warehouse/partner 档案 Bean）——库存/采购各表均无
 * 外键约束，测试自造 supplier_id/warehouse_id/product_id 隔离数据，单据状态流转与库存过账用
 * {@link TransactionTemplate} 提供外层事务（等价 AppService 的 @Transactional 边界）。发票建单需要
 * 供应商结算方式，由测试直接传入（生产由 app 层取自供应商档案）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class PurchasePostingIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static PurchaseOrderService orderService;
    private static PurchaseReceiptService receiptService;
    private static PurchaseInvoiceService invoiceService;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        DataSource migrationDataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        txTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        orderService = context.getBean(PurchaseOrderService.class);
        receiptService = context.getBean(PurchaseReceiptService.class);
        invoiceService = context.getBean(PurchaseInvoiceService.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({AuditConfig.class, InventoryInfraConfig.class, PurchaseInfraConfig.class})
    static class TestConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertyPlaceholder() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    @Test
    void 采购整链_下单到收货过账到开票应付_对账恒等式成立() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-IT-" + suffix;
        String prNo = "PR-IT-" + suffix;
        String pinvNo = "PINV-IT-" + suffix;
        LocalDate invoiceDate = LocalDate.of(2026, 6, 13);

        // 1. 下单：100 个 @12.50
        txTemplate.executeWithoutResult(s ->
                orderService.create(poNo, supplierId, invoiceDate, "整链采购",
                        List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                                new BigDecimal("12.50"))), OPERATOR));
        // 2. 审核
        txTemplate.executeWithoutResult(s -> orderService.approve(poNo, OPERATOR));

        // 3. 部分收货 60（收货单价默认取订单价 12.50），建单 → 审核 → 过账
        txTemplate.executeWithoutResult(s ->
                receiptService.create(prNo, poNo, warehouseId, invoiceDate, "首批",
                        List.of(new PurchaseReceiptLineInput(1, new BigDecimal("60"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> receiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                assertThat(receiptService.post(prNo, OPERATOR).getStatus().name()).isEqualTo("COMPLETED"));

        // 断言：库存增加 60 @12.50 = 750.00，产生 PURCHASE_IN 流水
        assertThat(txnType(warehouseId, productId)).containsExactly("PURCHASE_IN");
        assertBalanceQty(warehouseId, productId, "60");
        assertBalanceAmount(warehouseId, productId, "750.00");
        assertThat(idempotencyExists("PURCHASE_RECEIPT:" + prNo + ":1")).isTrue();
        // PO 行 received_qty 回写为 60（部分收货跟踪）
        PurchaseOrder afterReceipt = orderService.get(poNo);
        assertThat(afterReceipt.getLines().get(0).getReceivedQty()).isEqualByComparingTo("60");
        assertThat(afterReceipt.getLines().get(0).outstandingQty()).isEqualByComparingTo("40");

        // 4. 开发票（引用已过账收货单，开票 60、金额 800.00 含运费 50）→ 审核 → 过账
        txTemplate.executeWithoutResult(s ->
                invoiceService.create(pinvNo, prNo, supplierId, SettlementMethod.MONTHLY, invoiceDate,
                        "INV-IT", null, List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("60"),
                                new BigDecimal("800.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> invoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                assertThat(invoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR)
                        .getStatus().name()).isEqualTo("COMPLETED"));

        // 断言：生成一笔应付（供应商 / 金额 800.00 / 来源发票号 / 状态 OPEN / 月结到期日 = 发票日 +1 月）
        var payable = jdbc.queryForMap("SELECT supplier_id, amount, source_doc_no, due_date, status, "
                        + "settled_amount FROM accounts_payable WHERE tenant_id = 0 AND source_doc_no = ?",
                pinvNo);
        assertThat(((Number) payable.get("supplier_id")).longValue()).isEqualTo(supplierId);
        assertThat((BigDecimal) payable.get("amount")).isEqualByComparingTo("800.00");
        assertThat(payable.get("source_doc_no")).isEqualTo(pinvNo);
        assertThat(payable.get("status")).isEqualTo("OPEN");
        assertThat((BigDecimal) payable.get("settled_amount")).isEqualByComparingTo("0");
        assertThat(payable.get("due_date").toString()).isEqualTo(invoiceDate.plusMonths(1).toString());

        // 对账恒等式：Σ库存流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额
        assertLedgerIdentity(warehouseId, productId);

        // 单据均已完成
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE doc_no = ?",
                String.class, poNo)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_receipt WHERE doc_no = ?",
                String.class, prNo)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_invoice WHERE doc_no = ?",
                String.class, pinvNo)).isEqualTo("COMPLETED");
    }

    @Test
    void 三单匹配_开票数量超已收_整批回滚不生成应付() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-IT-" + suffix;
        String prNo = "PR-IT-" + suffix;
        String pinvNo = "PINV-IT-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        txTemplate.executeWithoutResult(s ->
                orderService.create(poNo, supplierId, d, null,
                        List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                                new BigDecimal("10"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> orderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                receiptService.create(prNo, poNo, warehouseId, d, null,
                        List.of(new PurchaseReceiptLineInput(1, new BigDecimal("60"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> receiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> receiptService.post(prNo, OPERATOR));

        // 已收 60，开票 70 > 60 → 建单即被拒（三单匹配），无发票无应付
        try {
            txTemplate.executeWithoutResult(s ->
                    invoiceService.create(pinvNo, prNo, supplierId, SettlementMethod.CASH, d, null, null,
                            List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("70"),
                                    new BigDecimal("700"))), OPERATOR));
            org.junit.jupiter.api.Assertions.fail("开票数量超已收应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期：三单匹配拒绝
        }
        Long payableCount = jdbc.queryForObject("SELECT COUNT(*) FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, pinvNo);
        assertThat(payableCount).isZero();
    }

    @Test
    void 跨发票超额开票_发票1全额过账后发票2再开同收货行被拒_不生成第二笔应付() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-IT-" + suffix;
        String prNo = "PR-IT-" + suffix;
        String pinv1 = "PINV1-IT-" + suffix;
        String pinv2 = "PINV2-IT-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        // 下单 100 → 审核 → 收 60 过账
        txTemplate.executeWithoutResult(s ->
                orderService.create(poNo, supplierId, d, null,
                        List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                                new BigDecimal("10"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> orderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                receiptService.create(prNo, poNo, warehouseId, d, null,
                        List.of(new PurchaseReceiptLineInput(1, new BigDecimal("60"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> receiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> receiptService.post(prNo, OPERATOR));

        // 发票1：全额 60 开票 → 审核 → 过账（回写收货行 invoiced_qty=60，生成第一笔应付）
        txTemplate.executeWithoutResult(s ->
                invoiceService.create(pinv1, prNo, supplierId, SettlementMethod.CASH, d, null, null,
                        List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("60"),
                                new BigDecimal("600"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> invoiceService.approve(pinv1, OPERATOR));
        txTemplate.executeWithoutResult(s -> invoiceService.post(pinv1, SettlementMethod.CASH, OPERATOR));
        // 收货行已开票量回写为 60
        assertThat(jdbc.queryForObject("SELECT invoiced_qty FROM purchase_receipt_line "
                        + "WHERE purchase_receipt_id = (SELECT id FROM purchase_receipt WHERE doc_no = ?) "
                        + "AND line_no = 1", BigDecimal.class, prNo)).isEqualByComparingTo("60");

        // 发票2：同收货行再开（剩余可开票量 = 0）→ 建单即被拒，不生成第二笔应付
        try {
            txTemplate.executeWithoutResult(s ->
                    invoiceService.create(pinv2, prNo, supplierId, SettlementMethod.CASH, d, null, null,
                            List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("60"),
                                    new BigDecimal("600"))), OPERATOR));
            org.junit.jupiter.api.Assertions.fail("跨发票超额开票应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期：剩余可开票量为 0
        }
        // 只生成第一笔应付（共 1 笔，发票1）；发票2无应付
        Long pinv2Payables = jdbc.queryForObject("SELECT COUNT(*) FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, pinv2);
        assertThat(pinv2Payables).isZero();
        Long pinv1Payables = jdbc.queryForObject("SELECT COUNT(*) FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, pinv1);
        assertThat(pinv1Payables).isEqualTo(1L);
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private List<String> txnType(long warehouseId, long productId) {
        return jdbc.queryForList("SELECT txn_type FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? ORDER BY id",
                String.class, warehouseId, productId);
    }

    private boolean idempotencyExists(String key) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction "
                + "WHERE tenant_id = 0 AND idempotency_key = ?", Long.class, key);
        return count != null && count > 0;
    }

    private void assertBalanceQty(long warehouseId, long productId, String expected) {
        BigDecimal qty = jdbc.queryForObject("SELECT quantity FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                BigDecimal.class, warehouseId, productId);
        assertThat(qty).as("商品 %d 余额数量", productId).isEqualByComparingTo(expected);
    }

    private void assertBalanceAmount(long warehouseId, long productId, String expected) {
        BigDecimal amount = jdbc.queryForObject("SELECT cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                BigDecimal.class, warehouseId, productId);
        assertThat(amount).as("商品 %d 余额金额", productId).isEqualByComparingTo(expected);
    }

    private void assertLedgerIdentity(long warehouseId, long productId) {
        var sums = jdbc.queryForMap("SELECT SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum "
                        + "FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                warehouseId, productId);
        var balance = jdbc.queryForMap("SELECT quantity, cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        assertThat((BigDecimal) sums.get("qty_sum")).as("商品 %d Σ流水数量 = 余额数量", productId)
                .isEqualByComparingTo((BigDecimal) balance.get("quantity"));
        assertThat((BigDecimal) sums.get("cost_sum")).as("商品 %d Σ流水金额 = 余额金额", productId)
                .isEqualByComparingTo((BigDecimal) balance.get("cost_amount"));
    }
}
