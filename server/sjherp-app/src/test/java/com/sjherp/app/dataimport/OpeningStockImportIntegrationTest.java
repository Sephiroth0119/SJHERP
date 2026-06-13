package com.sjherp.app.dataimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.app.config.AuditConfig;
import com.sjherp.app.config.CatalogInfraConfig;
import com.sjherp.app.config.InventoryInfraConfig;
import com.sjherp.app.config.WarehouseInfraConfig;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.inventory.JdbcStockChecker;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.inventory.StockChecker;
import com.sjherp.domain.warehouse.WarehouseCommand;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 期初库存导入集成测试（M2-T09 验收，@Tag integration-db，Testcontainers MySQL）。
 *
 * <p>测试覆盖（仿 InventoryPostingIntegrationTest 装配风格）：
 * <ul>
 *   <li>正常场景：样例 .xlsx → importOpeningStock → 断言每行一条 OPENING 流水，
 *       余额数量=数量且金额=数量×单价，Σ流水=余额恒等式；</li>
 *   <li>失败场景：含仓库解析失败的行 → 整体回滚（ImportRejectedException），
 *       断言无残留流水/余额。</li>
 * </ul>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行：
 * <pre>mvn test -pl sjherp-app -Dgroups=integration-db -DexcludedGroups=none</pre>
 */
@Tag("integration-db")
class OpeningStockImportIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ImportService importService;
    private static ProductService productService;
    private static UnitService unitService;
    private static WarehouseService warehouseService;

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
        importService = context.getBean(ImportService.class);
        productService = context.getBean(ProductService.class);
        unitService = context.getBean(UnitService.class);
        warehouseService = context.getBean(WarehouseService.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    // ----------------------------------------------------------------
    // 正常场景：期初导入→OPENING 流水→余额恒等式
    // ----------------------------------------------------------------

    @Test
    void 期初库存导入_两行_OPENING流水与余额恒等() throws Exception {
        // 前置：建仓库和商品档案
        String suffix = uniqueSuffix();
        String warehouseCode = "WH-TEST-" + suffix;
        String productCode = "SKU-TEST-" + suffix;

        long unitId = createUnit("个-" + suffix);
        createWarehouse(warehouseCode, "测试仓-" + suffix);
        createProduct(productCode, "测试商品-" + suffix, unitId);

        // 构造含两行的期初库存 xlsx：
        //   行1：warehouseCode, productCode, 100, 10.00
        //   行2：warehouseCode, productCode, 50, 20.00
        //   注意：两行同仓库同商品——期初只建一次；为避免冲突用不同商品
        String productCode2 = "SKU-TEST2-" + suffix;
        createProduct(productCode2, "测试商品2-" + suffix, unitId);

        byte[] xlsx = buildTwoRowXlsx(warehouseCode, productCode, "100", "10.00",
                warehouseCode, productCode2, "50", "20.00");
        MultipartFile file = mockFile(xlsx);

        var result = importService.importOpeningStock(file, OPERATOR);

        assertThat(result.success()).isTrue();
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);

        // 查询库存：warehouseId 通过编码查，productId 通过编码查
        long warehouseId = warehouseIdByCode(warehouseCode);
        long productId1 = productIdByCode(productCode);
        long productId2 = productIdByCode(productCode2);

        // 行1：数量=100，金额=1000.00
        assertBalance(warehouseId, productId1, "100", "1000.00");
        assertTransactionCount(warehouseId, productId1, 1, "OPENING");

        // 行2：数量=50，金额=1000.00
        assertBalance(warehouseId, productId2, "50", "1000.00");
        assertTransactionCount(warehouseId, productId2, 1, "OPENING");

        // 恒等式：Σ流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额
        assertReconciliation(warehouseId, productId1);
        assertReconciliation(warehouseId, productId2);
    }

    // ----------------------------------------------------------------
    // 失败场景：含仓库解析失败 → 整体回滚，无残留
    // ----------------------------------------------------------------

    @Test
    void 含解析失败行_整体回滚_无残留流水() throws Exception {
        String suffix = uniqueSuffix();
        String warehouseCode = "WH-RB-" + suffix;
        String productCode = "SKU-RB-" + suffix;

        long unitId = createUnit("件-" + suffix);
        createWarehouse(warehouseCode, "回滚测试仓-" + suffix);
        createProduct(productCode, "回滚测试商品-" + suffix, unitId);

        // 行1：有效行
        // 行2：仓库编码不存在 → 解析失败
        byte[] xlsx = buildTwoRowXlsx(
                warehouseCode, productCode, "30", "5.00",
                "不存在的仓库-" + suffix, productCode, "20", "3.00");
        MultipartFile file = mockFile(xlsx);

        assertThatThrownBy(() -> importService.importOpeningStock(file, OPERATOR))
                .isInstanceOf(ImportRejectedException.class)
                .satisfies(ex -> {
                    var rejected = (ImportRejectedException) ex;
                    var importResult = rejected.toResult();
                    assertThat(importResult.success()).isFalse();
                    assertThat(importResult.failures()).hasSize(1);
                    assertThat(importResult.failures().get(0).reason()).contains("仓库");
                    assertThat(importResult.succeeded()).isEqualTo(0);
                });

        long warehouseId = warehouseIdByCode(warehouseCode);
        long productId = productIdByCode(productCode);

        // 整体回滚：无残留流水，无余额行
        assertThat(transactionCount(warehouseId, productId)).as("回滚后无流水残留").isZero();
        assertThat(balanceRowCount(warehouseId, productId)).as("回滚后无余额残留").isZero();
    }

    // ----------------------------------------------------------------
    // TestConfig（与 InventoryPostingIntegrationTest 同风格）
    // ----------------------------------------------------------------

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({AuditConfig.class, CatalogInfraConfig.class, WarehouseInfraConfig.class,
            InventoryInfraConfig.class})
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

        /** 库存占用检查（仓库/商品停用前引用约束，JdbcStockChecker 只读 SQL 实现） */
        @Bean
        StockChecker stockChecker(JdbcTemplate jdbcTemplate) {
            return new JdbcStockChecker(jdbcTemplate);
        }

        /** 库存调整应用服务（ImportService 的依赖，开启期初建账路径） */
        @Bean
        InventoryAdjustmentService inventoryAdjustmentService(
                com.sjherp.app.config.TransactionalInventoryService inventoryService,
                WarehouseService warehouseService,
                ProductService productService,
                DocumentNumberGenerator numberGenerator) {
            return new InventoryAdjustmentService(inventoryService, warehouseService,
                    productService, numberGenerator);
        }

        /** ImportService：导入应用服务 */
        @Bean
        ImportService importService(ProductService productService,
                                    com.sjherp.domain.partner.CustomerService customerService,
                                    com.sjherp.domain.partner.SupplierService supplierService,
                                    WarehouseService warehouseService,
                                    UnitService unitService,
                                    InventoryAdjustmentService inventoryAdjustmentService) {
            return new ImportService(productService, customerService, supplierService,
                    warehouseService, unitService, inventoryAdjustmentService);
        }

        // 以下 Bean 是 ImportService 需要但 TestConfig 没有直接用到的（通过 CatalogInfraConfig 提供 productService/unitService）
        // CustomerService / SupplierService：ImportService 构造必须；实际测试不调用，但 Bean 需要存在
        @Bean
        com.sjherp.domain.partner.CustomerRepository customerRepository(JdbcTemplate jdbcTemplate) {
            return new com.sjherp.infra.persistence.partner.JdbcCustomerRepository(jdbcTemplate);
        }

        @Bean
        com.sjherp.domain.partner.CustomerService customerService(
                com.sjherp.domain.partner.CustomerRepository customerRepository,
                DocumentNumberGenerator numberGenerator) {
            return new com.sjherp.domain.partner.CustomerService(customerRepository, numberGenerator);
        }

        @Bean
        com.sjherp.domain.partner.SupplierRepository supplierRepository(JdbcTemplate jdbcTemplate) {
            return new com.sjherp.infra.persistence.partner.JdbcSupplierRepository(jdbcTemplate);
        }

        @Bean
        com.sjherp.domain.partner.SupplierService supplierService(
                com.sjherp.domain.partner.SupplierRepository supplierRepository,
                DocumentNumberGenerator numberGenerator) {
            return new com.sjherp.domain.partner.SupplierService(supplierRepository, numberGenerator);
        }
    }

    // ----------------------------------------------------------------
    // 辅助工具
    // ----------------------------------------------------------------

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static long createUnit(String name) {
        return unitService.create(name, 0, OPERATOR).getId();
    }

    private static void createWarehouse(String code, String name) {
        warehouseService.create(new WarehouseCommand(code, name, null, null, false), OPERATOR);
    }

    private static void createProduct(String code, String name, long unitId) {
        productService.create(new ProductCommand(code, name, null, null, unitId, null, null, java.util.List.of()),
                OPERATOR);
    }

    private static java.io.InputStream mockInputStream(byte[] bytes) {
        return new java.io.ByteArrayInputStream(bytes);
    }

    private static MultipartFile mockFile(byte[] content) throws Exception {
        var file = org.mockito.Mockito.mock(MultipartFile.class);
        org.mockito.Mockito.when(file.getInputStream())
                .thenReturn(new java.io.ByteArrayInputStream(content));
        return file;
    }

    private static byte[] buildTwoRowXlsx(String wh1, String prod1, String qty1, String cost1,
                                          String wh2, String prod2, String qty2, String cost2) {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sheet = wb.createSheet("导入数据");
            var h = sheet.createRow(0);
            h.createCell(0).setCellValue(ImportColumns.OPENING_WAREHOUSE);
            h.createCell(1).setCellValue(ImportColumns.OPENING_PRODUCT);
            h.createCell(2).setCellValue(ImportColumns.OPENING_QUANTITY);
            h.createCell(3).setCellValue(ImportColumns.OPENING_UNIT_COST);

            var r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(wh1);
            r1.createCell(1).setCellValue(prod1);
            r1.createCell(2).setCellValue(qty1);
            r1.createCell(3).setCellValue(cost1);

            var r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue(wh2);
            r2.createCell(1).setCellValue(prod2);
            r2.createCell(2).setCellValue(qty2);
            r2.createCell(3).setCellValue(cost2);

            var out = new java.io.ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("构造测试 xlsx 失败", e);
        }
    }

    private long warehouseIdByCode(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM warehouse WHERE code = ?", Long.class, code);
        assertThat(id).as("仓库 [%s] 应存在", code).isNotNull();
        return id;
    }

    private long productIdByCode(String code) {
        Long id = jdbc.queryForObject("SELECT id FROM product WHERE code = ?", Long.class, code);
        assertThat(id).as("商品 [%s] 应存在", code).isNotNull();
        return id;
    }

    private void assertBalance(long warehouseId, long productId, String expectedQty, String expectedAmount) {
        var balance = jdbc.queryForMap(
                "SELECT quantity, cost_amount FROM inventory_balance"
                        + " WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        assertThat((BigDecimal) balance.get("quantity"))
                .as("余额数量应为 %s", expectedQty)
                .isEqualByComparingTo(expectedQty);
        assertThat((BigDecimal) balance.get("cost_amount"))
                .as("余额金额应为 %s", expectedAmount)
                .isEqualByComparingTo(expectedAmount);
    }

    private void assertTransactionCount(long warehouseId, long productId, int expectedCount, String txnType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction"
                        + " WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND txn_type = ?",
                Long.class, warehouseId, productId, txnType);
        assertThat(count).as("期初流水条数应为 %d", expectedCount).isEqualTo(expectedCount);
    }

    private void assertReconciliation(long warehouseId, long productId) {
        var sums = jdbc.queryForMap(
                "SELECT SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum"
                        + " FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                warehouseId, productId);
        var balance = jdbc.queryForMap(
                "SELECT quantity, cost_amount FROM inventory_balance"
                        + " WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        assertThat((BigDecimal) sums.get("qty_sum"))
                .as("Σ流水数量 = 余额数量")
                .isEqualByComparingTo((BigDecimal) balance.get("quantity"));
        assertThat((BigDecimal) sums.get("cost_sum"))
                .as("Σ流水金额 = 余额金额")
                .isEqualByComparingTo((BigDecimal) balance.get("cost_amount"));
    }

    private long transactionCount(long warehouseId, long productId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction"
                        + " WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                Long.class, warehouseId, productId);
        return count == null ? -1 : count;
    }

    private long balanceRowCount(long warehouseId, long productId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_balance"
                        + " WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                Long.class, warehouseId, productId);
        return count == null ? -1 : count;
    }
}
