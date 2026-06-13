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

import com.sjherp.app.report.ReportQueryDao;
import com.sjherp.app.report.ReportQueryDao.InventoryBalanceReport;
import com.sjherp.app.report.ReportQueryDao.InventoryBalanceRow;
import com.sjherp.app.report.ReportQueryDao.PurchaseDetailReport;
import com.sjherp.app.report.ReportQueryDao.PurchaseDetailRow;
import com.sjherp.app.report.ReportQueryDao.SalesDetailReport;
import com.sjherp.app.report.ReportQueryDao.SalesDetailRow;
import com.sjherp.app.report.ReportQueryDao.StockMovementReport;
import com.sjherp.app.report.ReportQueryDao.StockMovementRow;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.purchase.PurchaseInvoiceLineInput;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.sales.SalesDeliveryLineInput;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesOrderLineInput;
import com.sjherp.domain.sales.SalesOrderService;

/**
 * 进销存报表只读集成测试（M3-T12，Testcontainers 真实 MySQL）。
 *
 * <p>沿用 {@link PurchaseToSalesFlowIntegrationTest} 同套装配（@Import 真实 {@link AuditConfig} +
 * {@link InventoryInfraConfig} + {@link PurchaseInfraConfig} + {@link SalesInfraConfig}），经各<b>领域服务</b>直驱
 * 种下完整链路，再篡改库存流水 created_at 制造期初/期内切分，最后对 {@link ReportQueryDao} 四个报表方法
 * 逐项断言（BigDecimal 一律 {@code isEqualByComparingTo} 比较，避免标度差异误判）。
 *
 * <p>不建主表档案（warehouse/product/supplier/customer）——报表主表一律 LEFT JOIN，容忍 null 名称，
 * 不会因档案缺失而丢行，正可验证「财务报表不静默丢行」铁律。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class ReportQueryIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static PurchaseOrderService purchaseOrderService;
    private static PurchaseReceiptService purchaseReceiptService;
    private static PurchaseInvoiceService purchaseInvoiceService;
    private static SalesOrderService salesOrderService;
    private static SalesDeliveryService salesDeliveryService;
    private static ReportQueryDao reportQueryDao;

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
        purchaseOrderService = context.getBean(PurchaseOrderService.class);
        purchaseReceiptService = context.getBean(PurchaseReceiptService.class);
        purchaseInvoiceService = context.getBean(PurchaseInvoiceService.class);
        salesOrderService = context.getBean(SalesOrderService.class);
        salesDeliveryService = context.getBean(SalesDeliveryService.class);
        reportQueryDao = context.getBean(ReportQueryDao.class);
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

        // 报表只读 DAO（生产由组件扫描装配；此处显式 new）
        @Bean
        ReportQueryDao reportQueryDao(JdbcTemplate jdbcTemplate) {
            return new ReportQueryDao(jdbcTemplate);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    @Test
    void 四报表数值正确_期初期内切分_合计与逐行恒等式() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-RPT-" + suffix;
        String prNo = "PR-RPT-" + suffix;
        String pinvNo = "PINV-RPT-" + suffix;
        String soNo = "SO-RPT-" + suffix;
        String sdNo = "SD-RPT-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        // ---- 采购线：下单 100@12.50 → 审核 → 收 100 过账（PURCHASE_IN，库存 100/1250.00） ----
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "报表采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d, "收货",
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.post(prNo, OPERATOR));

        // 采购发票：开 100 / 金额 1250.00 → 审核 → 过账（应付 1250.00；不影响报表，仅完整链路）
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-RPT", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY,
                OPERATOR));

        // ---- 销售线：下单 60@20 → 审核 → 出 60 过账（SALES_OUT，COGS=60×12.50=750.00，库存→40/500.00） ----
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, d, "报表销售",
                List.of(new SalesOrderLineInput(productId, new BigDecimal("60"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(soNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(sdNo, soNo, warehouseId, "发货",
                List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("60"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(sdNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.post(sdNo, OPERATOR));

        // ---- 篡改库存流水 created_at（仅测试构造已知时间口径——非业务路径）制造期初/期内切分 ----
        // PURCHASE_IN 置 6-01（期初前），SALES_OUT 置 6-15（期内）；据此跑收发存的两个期间窗口。
        jdbc.update("UPDATE inventory_transaction SET created_at = '2026-06-01 00:00:00' "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND txn_type = 'PURCHASE_IN'",
                warehouseId, productId);
        jdbc.update("UPDATE inventory_transaction SET created_at = '2026-06-15 00:00:00' "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND txn_type = 'SALES_OUT'",
                warehouseId, productId);

        // ============ 1. 库存余额表 ============
        InventoryBalanceReport balance =
                reportQueryDao.inventoryBalance(warehouseId, productId, null, true, 1, 20);
        assertThat(balance.page().items()).hasSize(1);
        InventoryBalanceRow balRow = balance.page().items().get(0);
        assertThat(balRow.warehouseId()).isEqualTo(warehouseId);
        assertThat(balRow.productId()).isEqualTo(productId);
        assertThat(balRow.quantity()).as("库存余额数量").isEqualByComparingTo("40");
        assertThat(balRow.costAmount()).as("库存余额金额").isEqualByComparingTo("500.00");
        // 派生加权单价 = 500.00 / 40 = 12.50
        assertThat(balRow.costAmount().divide(balRow.quantity(), 6, java.math.RoundingMode.HALF_UP))
                .as("派生加权单价").isEqualByComparingTo("12.50");
        assertThat(balance.totalCostAmount()).as("库存总值").isEqualByComparingTo("500.00");

        // ============ 2. 收发存汇总 ============
        // 2a. 期间 [6-10, 6-20]：期初(< 6-10)含 PURCHASE_IN(6-01)；期内含 SALES_OUT(6-15)
        StockMovementReport window = reportQueryDao.stockMovementSummary(
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 20),
                warehouseId, productId, null, 1, 20);
        assertThat(window.page().items()).hasSize(1);
        StockMovementRow mvRow = window.page().items().get(0);
        assertThat(mvRow.openingQuantity()).as("期初数量").isEqualByComparingTo("100");
        assertThat(mvRow.openingAmount()).as("期初金额").isEqualByComparingTo("1250.00");
        assertThat(mvRow.inQuantity()).as("期内入库数量").isEqualByComparingTo("0");
        assertThat(mvRow.inAmount()).as("期内入库金额").isEqualByComparingTo("0");
        assertThat(mvRow.outQuantity()).as("期内出库数量").isEqualByComparingTo("60");
        assertThat(mvRow.outAmount()).as("期内出库金额").isEqualByComparingTo("750.00");
        assertThat(mvRow.endingQuantity()).as("期末数量").isEqualByComparingTo("40");
        assertThat(mvRow.endingAmount()).as("期末金额").isEqualByComparingTo("500.00");
        // 显式 tie-out：期初 + 收 − 发 = 期末（数量与金额各一条）
        assertThat(mvRow.openingQuantity().add(mvRow.inQuantity()).subtract(mvRow.outQuantity()))
                .as("收发存数量恒等式 期初+收−发=期末").isEqualByComparingTo(mvRow.endingQuantity());
        assertThat(mvRow.openingAmount().add(mvRow.inAmount()).subtract(mvRow.outAmount()))
                .as("收发存金额恒等式 期初+收−发=期末").isEqualByComparingTo(mvRow.endingAmount());
        // 合计（窗口仅本行命中本维度过滤，故合计 = 行值）
        assertThat(window.summary().totalOpeningAmount()).isEqualByComparingTo("1250.00");
        assertThat(window.summary().totalInAmount()).isEqualByComparingTo("0");
        assertThat(window.summary().totalOutAmount()).isEqualByComparingTo("750.00");
        assertThat(window.summary().totalEndingAmount()).isEqualByComparingTo("500.00");

        // 2b. 全包含期间 [年初, 年末]：期初(< 1-1)=0、收=100、发=60、期末=40（验证期内聚合）
        StockMovementReport whole = reportQueryDao.stockMovementSummary(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                warehouseId, productId, null, 1, 20);
        assertThat(whole.page().items()).hasSize(1);
        StockMovementRow wholeRow = whole.page().items().get(0);
        assertThat(wholeRow.openingQuantity()).as("全包含期间期初数量").isEqualByComparingTo("0");
        assertThat(wholeRow.openingAmount()).as("全包含期间期初金额").isEqualByComparingTo("0");
        assertThat(wholeRow.inQuantity()).as("全包含期间入库数量").isEqualByComparingTo("100");
        assertThat(wholeRow.inAmount()).as("全包含期间入库金额").isEqualByComparingTo("1250.00");
        assertThat(wholeRow.outQuantity()).as("全包含期间出库数量").isEqualByComparingTo("60");
        assertThat(wholeRow.outAmount()).as("全包含期间出库金额").isEqualByComparingTo("750.00");
        assertThat(wholeRow.endingQuantity()).as("全包含期间期末数量").isEqualByComparingTo("40");
        assertThat(wholeRow.endingAmount()).as("全包含期间期末金额").isEqualByComparingTo("500.00");

        // ============ 3. 采购入库明细 ============
        PurchaseDetailReport purchase = reportQueryDao.purchaseDetail(
                null, null, null, productId, warehouseId, null, 1, 20);
        assertThat(purchase.page().items()).hasSize(1);
        PurchaseDetailRow pRow = purchase.page().items().get(0);
        assertThat(pRow.receiptNo()).isEqualTo(prNo);
        assertThat(pRow.lineNo()).isEqualTo(1);
        assertThat(pRow.quantity()).as("采购入库数量").isEqualByComparingTo("100");
        assertThat(pRow.unitCost()).as("采购入库单价").isEqualByComparingTo("12.50");
        assertThat(pRow.amount()).as("采购入库金额").isEqualByComparingTo("1250.00");
        assertThat(pRow.status()).as("采购入库单状态").isEqualTo("COMPLETED");
        assertThat(purchase.totalAmount()).as("采购总进货额").isEqualByComparingTo("1250.00");

        // ============ 4. 销售出库明细 ============
        SalesDetailReport sales = reportQueryDao.salesDetail(
                null, null, null, productId, warehouseId, null, 1, 20);
        assertThat(sales.page().items()).hasSize(1);
        SalesDetailRow sRow = sales.page().items().get(0);
        assertThat(sRow.deliveryNo()).isEqualTo(sdNo);
        assertThat(sRow.lineNo()).isEqualTo(1);
        assertThat(sRow.quantity()).as("销售出库数量").isEqualByComparingTo("60");
        // DAO 层 unitPrice 取销售订单行价(20)，不是发票价；cogsAmount 为移动加权成本原值(750.00)
        assertThat(sRow.unitPrice()).as("销售单价（订单行价）").isEqualByComparingTo("20");
        assertThat(sRow.cogsAmount()).as("销售 COGS").isEqualByComparingTo("750.00");
        assertThat(sRow.status()).as("销售出库单状态").isEqualTo("COMPLETED");
        // 自行用 BigDecimal 校验逐行销售额 60×20=1200.00（DTO 层口径）
        assertThat(sRow.quantity().multiply(sRow.unitPrice())).as("逐行销售额 数量×单价")
                .isEqualByComparingTo("1200.00");
        // 合计：销售额 1200.00、成本 750.00、毛利 450.00
        assertThat(sales.summary().totalSalesAmount()).as("销售总额").isEqualByComparingTo("1200.00");
        assertThat(sales.summary().totalCogsAmount()).as("销售总成本").isEqualByComparingTo("750.00");
        assertThat(sales.summary().totalGrossProfit()).as("销售总毛利").isEqualByComparingTo("450.00");
    }
}
