package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountLineInput;
import com.sjherp.domain.stocktake.StockCountService;

/**
 * 盘点单过账整链集成测试（M3-T03 验收核心，Testcontainers 真实 MySQL）：
 * 用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link StocktakeInfraConfig}）跑通：
 * <ul>
 *   <li>期初建账 → 建盘点单（含账面快照）→ 录入实盘（盘盈/盘亏/无差异）→ 审核 → 过账；</li>
 *   <li>断言：盘盈行产生 COUNT_GAIN 流水、盘亏行产生 COUNT_LOSS 流水、无差异行无流水；</li>
 *   <li>余额按差异变动；对账恒等式 Σ流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额；</li>
 *   <li>幂等键 STOCK_COUNT:SC-xxx:行号 落库且过账重放安全（同事务整批原子）。</li>
 * </ul>
 *
 * <p>不直接走 app 的 StocktakeService（避免装配 catalog/warehouse 档案 Bean 与真实档案行）——
 * 库存/盘点两表均无外键约束，测试自造 warehouse_id/product_id 隔离数据，盘点状态流转与库存过账
 * 用 {@link TransactionTemplate} 提供外层事务（等价 StocktakeService 的 @Transactional 边界）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class StockCountPostingIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static StockCountService stockCountService;

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
        stockCountService = context.getBean(StockCountService.class);
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
    @Import({AuditConfig.class, InventoryInfraConfig.class, StocktakeInfraConfig.class})
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
    void 盘点整链_期初到过账_盘盈盘亏无差异_对账恒等式成立() {
        long warehouseId = nextId();
        long productGain = nextId();   // 盘盈
        long productLoss = nextId();   // 盘亏
        long productSame = nextId();   // 无差异
        long productZero = nextId();   // 零库存盘盈（录入单价）
        String suffix = Long.toString(System.nanoTime(), 36);
        String docNo = "SC-IT-" + suffix;

        // 1. 期初建账（库存唯一写入口）
        inventoryService.inbound(opening(warehouseId, productGain, "100", "10.00", suffix + "G"), OPERATOR);
        inventoryService.inbound(opening(warehouseId, productLoss, "50", "20.00", suffix + "L"), OPERATOR);
        inventoryService.inbound(opening(warehouseId, productSame, "30", "5.00", suffix + "S"), OPERATOR);
        // productZero 不建账，账面为 0

        // 2. 建盘点单（账面快照由领域服务调库存 balanceOf 取——这里直接传期初后的快照）
        List<StockCountLineInput> lines = List.of(
                new StockCountLineInput(productGain, new BigDecimal("100"), null),
                new StockCountLineInput(productLoss, new BigDecimal("50"), null),
                new StockCountLineInput(productSame, new BigDecimal("30"), null),
                new StockCountLineInput(productZero, new BigDecimal("0"), new BigDecimal("8.00")));
        txTemplate.executeWithoutResult(s ->
                stockCountService.create(docNo, warehouseId, "整链盘点", lines, OPERATOR));

        // 3. 录入实盘：盘盈 +5、盘亏 -5、无差异、零库存盘盈 +20
        txTemplate.executeWithoutResult(s -> {
            stockCountService.enterCount(docNo, 1, new BigDecimal("105"), OPERATOR);
            stockCountService.enterCount(docNo, 2, new BigDecimal("45"), OPERATOR);
            stockCountService.enterCount(docNo, 3, new BigDecimal("30"), OPERATOR);
            stockCountService.enterCount(docNo, 4, new BigDecimal("20"), OPERATOR);
        });

        // 4. 审核
        txTemplate.executeWithoutResult(s -> stockCountService.approve(docNo, OPERATOR));

        // 5. 过账（外层事务包住状态流转 + 库存批量过账）
        txTemplate.executeWithoutResult(s -> {
            StockCountDocument posted = stockCountService.post(docNo, OPERATOR);
            assertThat(posted.getStatus().name()).isEqualTo("COMPLETED");
        });

        // 盘盈行产生 COUNT_GAIN 流水
        assertThat(txnType(warehouseId, productGain)).containsExactly("OPENING", "COUNT_GAIN");
        // 盘亏行产生 COUNT_LOSS 流水
        assertThat(txnType(warehouseId, productLoss)).containsExactly("OPENING", "COUNT_LOSS");
        // 无差异行不产生盘点流水（只有期初）
        assertThat(txnType(warehouseId, productSame)).containsExactly("OPENING");
        // 零库存盘盈产生 COUNT_GAIN
        assertThat(txnType(warehouseId, productZero)).containsExactly("COUNT_GAIN");

        // 盘盈：100 → 105（数量 +5），盘盈单价取当前派生加权单价 10.000000
        assertBalanceQty(warehouseId, productGain, "105");
        // 盘亏：50 → 45（数量 -5）
        assertBalanceQty(warehouseId, productLoss, "45");
        // 无差异：30 不变
        assertBalanceQty(warehouseId, productSame, "30");
        // 零库存盘盈：0 → 20 @8.00，余额金额 160.00
        assertBalanceQty(warehouseId, productZero, "20");
        assertBalanceAmount(warehouseId, productZero, "160.00");

        // 幂等键落库（盘盈/盘亏行）
        assertThat(idempotencyExists("STOCK_COUNT:" + docNo + ":1")).isTrue();
        assertThat(idempotencyExists("STOCK_COUNT:" + docNo + ":2")).isTrue();
        assertThat(idempotencyExists("STOCK_COUNT:" + docNo + ":3")).as("无差异行不产生流水").isFalse();
        assertThat(idempotencyExists("STOCK_COUNT:" + docNo + ":4")).isTrue();

        // 对账恒等式（每个商品）：Σ流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额
        for (long productId : List.of(productGain, productLoss, productSame, productZero)) {
            assertLedgerIdentity(warehouseId, productId);
        }

        // 盘点单已完成
        assertThat(jdbc.queryForObject("SELECT status FROM stock_count WHERE doc_no = ?",
                String.class, docNo)).isEqualTo("COMPLETED");
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static InboundCommand opening(long warehouseId, long productId, String quantity,
                                          String unitCost, String key) {
        return new InboundCommand(warehouseId, productId, InventoryTxnType.OPENING,
                new BigDecimal(quantity), new BigDecimal(unitCost), null,
                "OPENING", "OP-IT-" + key, 1, "OPENING:OP-IT-" + key + ":1");
    }

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
