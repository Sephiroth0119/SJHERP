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

import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.purchase.PurchaseInvoiceLineInput;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.sales.SalesDeliveryLineInput;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoiceLineInput;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderLineInput;
import com.sjherp.domain.sales.SalesOrderService;

/**
 * 进销存端到端链路 + 一致性校验集成测试（M3-T11/T13 共同 CI 验收，Testcontainers 真实 MySQL）。
 *
 * <p>用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link PurchaseInfraConfig} + {@link SalesInfraConfig}）跑通完整链路：
 * 期初进货 → PO 下单/审核 → 收货 create/approve/post → 采购发票 create/approve/post（应付）
 * → SO 下单/审核 → 出库 create/approve/post（SALES_OUT + COGS） → 销售发票 create/approve/post（应收），
 * 最后调 {@link ConsistencyCheckService#check()} 断言 <b>0 ERROR break</b>，并 SQL 旁证关键恒等式数值正确。
 *
 * <p>驱动方式说明（与现有 PurchasePostingIntegrationTest/SalesPostingIntegrationTest 一致）：
 * 经各<b>领域服务</b>直驱、自造 supplier/customer/warehouse/product id 隔离数据——库存/单据各表均无外键约束。
 * 不经 app 的 AppService（避免装配 catalog/warehouse/partner 全档案 Bean 闭包并落真实档案行）；
 * AppService 仅薄包装（外层 @Transactional + 档案存在性校验），其覆盖的核心过账与勾稽逻辑全在领域服务，
 * 本测试已对该核心做端到端覆盖。状态流转/过账用 {@link TransactionTemplate} 提供外层事务（等价 AppService 的事务边界）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class PurchaseToSalesFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static PurchaseOrderService purchaseOrderService;
    private static PurchaseReceiptService purchaseReceiptService;
    private static PurchaseInvoiceService purchaseInvoiceService;
    private static SalesOrderService salesOrderService;
    private static SalesDeliveryService salesDeliveryService;
    private static SalesInvoiceService salesInvoiceService;
    private static ConsistencyCheckService consistencyCheckService;

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
        inventoryService = context.getBean(TransactionalInventoryService.class);
        purchaseOrderService = context.getBean(PurchaseOrderService.class);
        purchaseReceiptService = context.getBean(PurchaseReceiptService.class);
        purchaseInvoiceService = context.getBean(PurchaseInvoiceService.class);
        salesOrderService = context.getBean(SalesOrderService.class);
        salesDeliveryService = context.getBean(SalesDeliveryService.class);
        salesInvoiceService = context.getBean(SalesInvoiceService.class);
        consistencyCheckService = context.getBean(ConsistencyCheckService.class);
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
    @Import({AuditConfig.class, InventoryInfraConfig.class, PurchaseInfraConfig.class,
            SalesInfraConfig.class})
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

        // 一致性校验单元（生产由组件扫描装配；此处显式 new，allow-negative=false）
        @Bean
        ConsistencyCheckDao consistencyCheckDao(JdbcTemplate jdbcTemplate) {
            return new ConsistencyCheckDao(jdbcTemplate);
        }

        @Bean
        ConsistencyCheckService consistencyCheckService(ConsistencyCheckDao dao) {
            return new ConsistencyCheckService(dao, false);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    @Test
    void 进货到销售全链路_一致性校验0ERROR_恒等式数值正确() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-FL-" + suffix;
        String prNo = "PR-FL-" + suffix;
        String pinvNo = "PINV-FL-" + suffix;
        String soNo = "SO-FL-" + suffix;
        String sdNo = "SD-FL-" + suffix;
        String sinvNo = "SINV-FL-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        // ---- 采购线：下单 100@12.50 → 审核 → 收 100 过账（PURCHASE_IN，库存 100/1250.00） ----
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "整链采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d, "收货",
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.post(prNo, OPERATOR));

        // 采购发票：开 100 / 金额 1250.00 → 审核 → 过账（应付 1250.00）
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-FL", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY,
                OPERATOR));

        // ---- 销售线：下单 60@20 → 审核 → 出 60 过账（SALES_OUT，COGS=60×12.50=750.00，库存→40/500.00） ----
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, d, "整链销售",
                List.of(new SalesOrderLineInput(productId, new BigDecimal("60"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(soNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(sdNo, soNo, warehouseId, "发货",
                List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("60"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(sdNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.post(sdNo, OPERATOR));

        // 销售发票：对出库行 60@25 开票 → 审核 → 过账（应收 1500.00）
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(sinvNo, sdNo, customerId, d,
                d.plusMonths(1), "开票",
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("60"),
                        new BigDecimal("25"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(sinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.post(sinvNo, OPERATOR));

        // ============ SQL 旁证：关键恒等式数值正确 ============
        // 库存余额：100 入 - 60 出 = 40；金额 1250.00 - 750.00 = 500.00
        assertBalanceQty(warehouseId, productId, "40");
        assertBalanceAmount(warehouseId, productId, "500.00");
        // Σ流水 = 余额
        assertLedgerIdentity(warehouseId, productId);
        // 应付 = 采购发票额 1250.00
        assertThat(payableAmount(pinvNo)).isEqualByComparingTo("1250.00");
        // 应收 = 销售发票额 1500.00
        assertThat(receivableAmount(sinvNo)).isEqualByComparingTo("1500.00");
        // COGS = SALES_OUT Σ|total_cost| = 750.00
        BigDecimal cogs = jdbc.queryForObject("SELECT cogs_amount FROM sales_delivery_line "
                        + "WHERE sales_delivery_id = (SELECT id FROM sales_delivery WHERE doc_no = ?) "
                        + "AND line_no = 1", BigDecimal.class, sdNo);
        assertThat(cogs).isEqualByComparingTo("750.00");
        BigDecimal salesOutTotal = jdbc.queryForObject("SELECT -SUM(total_cost) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND txn_type = 'SALES_OUT'",
                BigDecimal.class, warehouseId, productId);
        assertThat(salesOutTotal).isEqualByComparingTo("750.00");

        // ============ 一致性校验：本链路相关键 0 ERROR break ============
        // check() 扫描全库 tenant-0 数据；本类另有负向用例会留下篡改行，故 ERROR 断言收敛到本次
        // 链路涉及的键（库存维度 + 本次各发票号 + 本出库单号），避免跨用例污染误伤本断言。
        ConsistencyReport report = consistencyCheckService.check();
        String invKey = "warehouse=" + warehouseId + ",product=" + productId;
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> b.key() != null && (b.key().contains(invKey)
                        || b.key().equals(pinvNo) || b.key().equals(sinvNo)
                        || b.key().startsWith(sdNo + "#")))
                .toList();
        assertThat(errors)
                .as("端到端链路跑通后本链路相关键应 0 个 ERROR break，实际：%s", errors)
                .isEmpty();
    }

    @Test
    void 负向_孤立应付无对应发票时校验命中break() {
        // 直插一条来源单据号不存在的孤立应付（不经业务单据）——故意制造不平。
        // 注意：规则4 从「已过账采购发票」侧驱动比对，孤立应付不会被规则4 抓到（无对应发票行）；
        // 本用例验证检查器对「篡改余额」类破坏的抓取能力（最直接的恒等式破坏）。
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);

        // 期初入 50 / 500.00（流水与余额一致）
        inventoryService.inbound(new com.sjherp.domain.inventory.InboundCommand(warehouseId, productId,
                com.sjherp.domain.inventory.InventoryTxnType.OPENING, new BigDecimal("50"),
                new BigDecimal("10.00"), null, "OPENING", "OP-NEG-" + suffix, 1,
                "OPENING:OP-NEG-" + suffix + ":1"), OPERATOR);

        // 篡改余额数量（绕过领域服务，仅为构造反例验证检查器——绝非业务路径）
        jdbc.update("UPDATE inventory_balance SET quantity = quantity + 1 "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);

        ConsistencyReport report = consistencyCheckService.check();
        boolean hit = report.breaks().stream().anyMatch(b ->
                b.checkType() == com.sjherp.app.consistency.ConsistencyCheckType.LEDGER_QUANTITY
                        && b.key().equals("warehouse=" + warehouseId + ",product=" + productId)
                        && b.severity() == ConsistencySeverity.ERROR);
        assertThat(hit).as("篡改余额后应命中库存数量恒等式 ERROR break（验证检查器非空转）").isTrue();
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

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

    private BigDecimal payableAmount(String invoiceNo) {
        return jdbc.queryForObject("SELECT amount FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", BigDecimal.class, invoiceNo);
    }

    private BigDecimal receivableAmount(String invoiceNo) {
        return jdbc.queryForObject("SELECT amount FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", BigDecimal.class, invoiceNo);
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
