package com.sjherp.app.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.config.AuditConfig;
import com.sjherp.app.config.CatalogInfraConfig;
import com.sjherp.app.config.CollectionInfraConfig;
import com.sjherp.app.config.FundInfraConfig;
import com.sjherp.app.config.GlInfraConfig;
import com.sjherp.app.config.InventoryInfraConfig;
import com.sjherp.app.config.PartnerInfraConfig;
import com.sjherp.app.config.PaymentInfraConfig;
import com.sjherp.app.config.PurchaseInfraConfig;
import com.sjherp.app.config.SalesInfraConfig;
import com.sjherp.app.config.SettlementInfraConfig;
import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.config.WarehouseInfraConfig;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.inventory.JdbcStockChecker;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.collection.CollectionReceiptService;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.gap.GapRecordRepository;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.inventory.StockChecker;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payment.PaymentDisbursementService;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.receivable.ReceivableRepository;
import com.sjherp.domain.receivable.ReceivableService;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderService;
import com.sjherp.domain.settlement.SettlementRecordRepository;
import com.sjherp.domain.settlement.SettlementService;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.persistence.gap.JdbcGapRecordRepository;

/**
 * 演示种子真库验收（M8-T07）：经 AppService 走完进销存+收付款，关账预检可关且 0 ERROR，账期仍 OPEN。
 */
@Tag("integration-db")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSeedFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-demo-seed";

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static DemoSeedService demoSeedService;
    private static AccountingPeriodService accountingPeriodService;

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
        demoSeedService = context.getBean(DemoSeedService.class);
        accountingPeriodService = context.getBean(AccountingPeriodService.class);
    }

    @AfterAll
    static void tearDown() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            // 必须显式 stop：多数兄弟类把容器留给 Ryuk 直到 JVM 退出。
            // 本类若再泄漏一台 mysql:8.4，GitHub runner aio-max-nr 会在后续
            // DeveloperAgentStartRollback.setUp 上 io_setup EAGAIN。
            MYSQL.stop();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({AuditConfig.class, CatalogInfraConfig.class, WarehouseInfraConfig.class,
            PartnerInfraConfig.class, InventoryInfraConfig.class, PurchaseInfraConfig.class,
            SalesInfraConfig.class, GlInfraConfig.class, SettlementInfraConfig.class,
            FundInfraConfig.class, CollectionInfraConfig.class, PaymentInfraConfig.class})
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

        @Bean
        StockChecker stockChecker(JdbcTemplate jdbcTemplate) {
            return new JdbcStockChecker(jdbcTemplate);
        }

        @Bean
        InventoryAdjustmentService inventoryAdjustmentService(
                TransactionalInventoryService inventoryService,
                WarehouseService warehouseService,
                ProductService productService,
                DocumentNumberGenerator numberGenerator) {
            return new InventoryAdjustmentService(inventoryService, warehouseService,
                    productService, numberGenerator);
        }

        @Bean
        GapRecordRepository gapRecordRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcGapRecordRepository(jdbcTemplate);
        }

        @Bean
        GapRecordService gapRecordService(GapRecordRepository gapRecordRepository,
                                          DocumentNumberGenerator documentNumberGenerator) {
            return new GapRecordService(gapRecordRepository, documentNumberGenerator);
        }

        @Bean
        ConsistencyCheckDao consistencyCheckDao(JdbcTemplate jdbcTemplate) {
            return new ConsistencyCheckDao(jdbcTemplate);
        }

        @Bean
        ConsistencyCheckService consistencyCheckService(ConsistencyCheckDao dao) {
            return new ConsistencyCheckService(dao, false);
        }

        @Bean
        PeriodCloseService periodCloseService(VoucherService voucherService,
                                              AccountService accountService,
                                              AccountingPeriodService accountingPeriodService,
                                              ConsistencyCheckService consistencyCheckService,
                                              DocumentNumberGenerator documentNumberGenerator) {
            return new PeriodCloseService(voucherService, accountService, accountingPeriodService,
                    consistencyCheckService, documentNumberGenerator);
        }

        @Bean
        VoucherAppService voucherAppService(VoucherService voucherService,
                                            DocumentNumberGenerator documentNumberGenerator) {
            return new VoucherAppService(voucherService, documentNumberGenerator);
        }

        @Bean
        PurchaseOrderAppService purchaseOrderAppService(PurchaseOrderService purchaseOrderService,
                                                        SupplierService supplierService,
                                                        ProductService productService,
                                                        DocumentNumberGenerator numberGenerator) {
            return new PurchaseOrderAppService(purchaseOrderService, supplierService, productService,
                    numberGenerator);
        }

        @Bean
        PurchaseReceiptAppService purchaseReceiptAppService(
                PurchaseReceiptService purchaseReceiptService,
                WarehouseService warehouseService,
                DocumentNumberGenerator numberGenerator,
                AutoVoucherService autoVoucherService,
                VoucherService voucherService,
                VoucherAppService voucherAppService) {
            return new PurchaseReceiptAppService(purchaseReceiptService, warehouseService, numberGenerator,
                    autoVoucherService, voucherService, voucherAppService);
        }

        @Bean
        PurchaseInvoiceAppService purchaseInvoiceAppService(
                PurchaseInvoiceService purchaseInvoiceService,
                PurchaseReceiptService purchaseReceiptService,
                PurchaseOrderService purchaseOrderService,
                SupplierService supplierService,
                AccountsPayableRepository accountsPayableRepository,
                DocumentNumberGenerator numberGenerator,
                AutoVoucherService autoVoucherService,
                VoucherService voucherService,
                VoucherAppService voucherAppService) {
            return new PurchaseInvoiceAppService(purchaseInvoiceService, purchaseReceiptService,
                    purchaseOrderService, supplierService, accountsPayableRepository, numberGenerator,
                    autoVoucherService, voucherService, voucherAppService);
        }

        @Bean
        SalesOrderAppService salesOrderAppService(SalesOrderService salesOrderService,
                                                  CustomerService customerService,
                                                  ProductService productService,
                                                  TransactionalInventoryService inventoryService,
                                                  DocumentNumberGenerator numberGenerator) {
            return new SalesOrderAppService(salesOrderService, customerService, productService,
                    inventoryService, numberGenerator, true);
        }

        @Bean
        SalesDeliveryAppService salesDeliveryAppService(SalesDeliveryService salesDeliveryService,
                                                        WarehouseService warehouseService,
                                                        DocumentNumberGenerator numberGenerator,
                                                        AutoVoucherService autoVoucherService,
                                                        VoucherService voucherService,
                                                        VoucherAppService voucherAppService) {
            return new SalesDeliveryAppService(salesDeliveryService, warehouseService, numberGenerator,
                    autoVoucherService, voucherService, voucherAppService);
        }

        @Bean
        SalesInvoiceAppService salesInvoiceAppService(SalesInvoiceService salesInvoiceService,
                                                      SalesDeliveryService salesDeliveryService,
                                                      SalesOrderService salesOrderService,
                                                      DocumentNumberGenerator numberGenerator,
                                                      AutoVoucherService autoVoucherService,
                                                      VoucherService voucherService,
                                                      VoucherAppService voucherAppService) {
            return new SalesInvoiceAppService(salesInvoiceService, salesDeliveryService, salesOrderService,
                    numberGenerator, autoVoucherService, voucherService, voucherAppService);
        }

        @Bean
        CollectionReceiptAppService collectionReceiptAppService(
                CollectionReceiptService collectionReceiptService,
                PaymentAccountService paymentAccountService,
                ReceivableService receivableService,
                SettlementService settlementService,
                SettlementRecordRepository settlementRecordRepository,
                AutoVoucherService autoVoucherService,
                VoucherService voucherService,
                VoucherAppService voucherAppService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new CollectionReceiptAppService(collectionReceiptService, paymentAccountService,
                    receivableService, settlementService, settlementRecordRepository, autoVoucherService,
                    voucherService, voucherAppService, documentNumberGenerator);
        }

        @Bean
        PaymentDisbursementAppService paymentDisbursementAppService(
                PaymentDisbursementService paymentDisbursementService,
                PaymentAccountService paymentAccountService,
                AccountsPayableRepository payableRepository,
                SettlementService settlementService,
                SettlementRecordRepository settlementRecordRepository,
                AutoVoucherService autoVoucherService,
                VoucherService voucherService,
                VoucherAppService voucherAppService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new PaymentDisbursementAppService(paymentDisbursementService, paymentAccountService,
                    payableRepository, settlementService, settlementRecordRepository, autoVoucherService,
                    voucherService, voucherAppService, documentNumberGenerator);
        }

        @Bean
        DemoSeedService demoSeedService(UnitService unitService,
                                        ProductService productService,
                                        WarehouseService warehouseService,
                                        SupplierService supplierService,
                                        CustomerService customerService,
                                        PaymentAccountService paymentAccountService,
                                        InventoryAdjustmentService inventoryAdjustmentService,
                                        PurchaseOrderAppService purchaseOrderAppService,
                                        PurchaseReceiptAppService purchaseReceiptAppService,
                                        PurchaseInvoiceAppService purchaseInvoiceAppService,
                                        PaymentDisbursementAppService paymentDisbursementAppService,
                                        SalesOrderAppService salesOrderAppService,
                                        SalesDeliveryAppService salesDeliveryAppService,
                                        SalesInvoiceAppService salesInvoiceAppService,
                                        CollectionReceiptAppService collectionReceiptAppService,
                                        AccountingPeriodService accountingPeriodService,
                                        PeriodCloseService periodCloseService,
                                        AccountsPayableRepository accountsPayableRepository,
                                        ReceivableRepository receivableRepository,
                                        GapRecordService gapRecordService,
                                        PlatformTransactionManager transactionManager) {
            return new DemoSeedService(unitService, productService, warehouseService, supplierService,
                    customerService, paymentAccountService, inventoryAdjustmentService,
                    purchaseOrderAppService, purchaseReceiptAppService, purchaseInvoiceAppService,
                    paymentDisbursementAppService, salesOrderAppService, salesDeliveryAppService,
                    salesInvoiceAppService, collectionReceiptAppService, accountingPeriodService,
                    periodCloseService, accountsPayableRepository, receivableRepository,
                    gapRecordService, transactionManager);
        }
    }

    @Test
    @Order(1)
    void sunshine首次装载_走完建审过账_预检可关且不关账() {
        DemoSeedResult result = demoSeedService.seed("sunshine", OPERATOR);

        assertThat(result.alreadyLoaded()).isFalse();
        assertThat(result.packId()).isEqualTo("sunshine");
        assertThat(result.companyLabel()).isEqualTo("演示阳光文具");
        assertThat(result.warehouseName()).isEqualTo("演示阳光仓");
        assertThat(result.tradeProductName()).startsWith("演示");
        assertThat(result.openingProductName()).startsWith("演示");
        assertThat(result.purchaseOrderNo()).startsWith("PO-");
        assertThat(result.purchaseReceiptNo()).startsWith("PR-");
        assertThat(result.purchaseInvoiceNo()).startsWith("PINV-");
        assertThat(result.paymentDocNo()).startsWith("PAYV-");
        assertThat(result.salesOrderNo()).startsWith("SO-");
        assertThat(result.salesDeliveryNo()).startsWith("SD-");
        assertThat(result.salesInvoiceNo()).startsWith("SINV-");
        assertThat(result.collectionDocNo()).startsWith("RCPT-");
        assertThat(result.sampleGapNo()).startsWith("GAP-");
        assertThat(result.periodOpen()).isTrue();
        assertThat(result.consistencyErrors()).isEmpty();
        assertThat(result.periodCloseable())
                .as("关账预检应可关；若失败原因=%s", result.consistencyErrors())
                .isTrue();
        assertThat(accountingPeriodService.isOpen(result.period())).isTrue();

        Integer closed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounting_period WHERE tenant_id=0 AND period=? AND status='CLOSED'",
                Integer.class, result.period());
        assertThat(closed).as("种子不得关账").isZero();

        Integer seededOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_order WHERE tenant_id=0 AND remark LIKE ?",
                Integer.class, "%SJHERP-DEMO-SEED:sunshine%");
        assertThat(seededOrders).isEqualTo(1);

        Integer gaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gap_record WHERE tenant_id=0 AND title LIKE ?",
                Integer.class, DemoSeedService.SAMPLE_GAP_PREFIX + "%");
        assertThat(gaps).isEqualTo(1);
    }

    @Test
    @Order(2)
    void 再次装载幂等跳过() {
        DemoSeedResult first = demoSeedService.seed("sunshine", OPERATOR);
        DemoSeedResult second = demoSeedService.seed("sunshine", OPERATOR);

        assertThat(second.alreadyLoaded()).isTrue();
        assertThat(second.purchaseOrderNo()).isEqualTo(first.purchaseOrderNo());
        assertThat(second.periodCloseable()).isTrue();
        assertThat(accountingPeriodService.isOpen(second.period())).isTrue();

        Integer seededOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_order WHERE tenant_id=0 AND remark LIKE ?",
                Integer.class, "%SJHERP-DEMO-SEED:sunshine%");
        assertThat(seededOrders).isEqualTo(1);
    }

    @Test
    @Order(3)
    void 单租户已装sunshine则拒绝qinghe() {
        assertThatThrownBy(() -> demoSeedService.seed("qinghe", OPERATOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sunshine");
    }
}
