package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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

import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.stocktake.StockCountLineInput;
import com.sjherp.domain.stocktake.StockCountService;
import com.sjherp.domain.transfer.TransferLineInput;
import com.sjherp.domain.transfer.TransferService;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 调拨单 + 盘点单红冲真库集成测试（M4-T07c 库存侧验收，Testcontainers 真实 MySQL，
 * 设计真源 docs/M4拆解-统一冲销机制.md §75/§77：调拨/盘点不出 GL 凭证，红冲只对称反向库存）。
 *
 * <p>装配蓝本：{@link StockCountPostingIntegrationTest}（库存自造 id 隔离 + 期初建账）+
 * {@link BusinessDocReversalFlowIntegrationTest}（reverse 走 AppService @Transactional 边界 + 一致性 0 ERROR）。
 * 调拨/盘点的建单/过账经<b>领域服务</b>直驱（绕开 catalog/warehouse 档案校验，库存与单据表无外键），
 * <b>红冲（reverse）经 AppService</b>（{@link TransferAppService#reverse} / {@link StocktakeService#reverse}）
 * ——验证库存两腿/各行反向经 {@link TransactionalInventoryService}（REQUIRED）入 AppService 外层事务原子提交。
 *
 * <h2>三组验收</h2>
 * <ol>
 *   <li><b>调拨红冲</b>：两仓调拨 post（调出仓 −100/−1250、调入仓 +100/+1250）→ reverse：两仓库存数量/成本
 *       双双回到调拨前（守恒归位）、反向两腿流水按原成本、调拨单 REVERSED；</li>
 *   <li><b>盘点红冲</b>：盘盈 + 盘亏 post → reverse：库存回到盘点前（盘盈反向出库按原盘盈单价、盘亏反向入库按原
 *       盘亏成本）、盘点单 REVERSED；零差异行无反向流水；</li>
 *   <li><b>幂等 + 一致性</b>：已 REVERSED 单据再 reverse 被拒（领域 IllegalState 整事务回滚）、库存账实归位后
 *       {@link ConsistencyCheckService#check()} 本链路库存键 0 ERROR（Σ流水=余额）、审计 {@code *.reverse}。</li>
 * </ol>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class InventoryReversalFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-invrev";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static TransferService transferService;
    private static StockCountService stockCountService;
    private static TransferAppService transferAppService;
    private static StocktakeService stocktakeService;
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
        transferService = context.getBean(TransferService.class);
        stockCountService = context.getBean(StockCountService.class);
        transferAppService = context.getBean(TransferAppService.class);
        stocktakeService = context.getBean(StocktakeService.class);
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
    @Import({AuditConfig.class, InventoryInfraConfig.class, TransferInfraConfig.class,
            StocktakeInfraConfig.class})
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

        /** reverse 路径不触档案：注入 mock（建单经领域服务直驱绕开档案校验）。 */
        @Bean
        WarehouseService warehouseService() {
            return mock(WarehouseService.class);
        }

        @Bean
        ProductService productService() {
            return mock(ProductService.class);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator() {
            // reverse 不取号；建单走领域服务自带单号，故 AppService 的取号器本测不被调用，mock 占位
            return mock(DocumentNumberGenerator.class);
        }

        @Bean
        TransferAppService transferAppService(TransferService transferService,
                WarehouseService warehouseService, ProductService productService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new TransferAppService(transferService, warehouseService, productService,
                    documentNumberGenerator);
        }

        @Bean
        StocktakeService stocktakeService(StockCountService stockCountService,
                WarehouseService warehouseService, ProductService productService,
                TransactionalInventoryService transactionalInventoryService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new StocktakeService(stockCountService, warehouseService, productService,
                    transactionalInventoryService, documentNumberGenerator);
        }

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

    // =====================================================================
    // 验收①：调拨红冲——两仓库存守恒归位 + 反向两腿流水按原成本 + 调拨单 REVERSED + 幂等 + 一致性
    // =====================================================================

    @Test
    void 调拨红冲_两仓库存守恒归位_反向两腿按原成本_REVERSED_再冲被拒_一致性0ERROR() {
        long fromWh = nextId();
        long toWh = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String trNo = "TR-IR-" + suffix;

        // 期初：调出仓建账 100@12.50（库存 100/1250.00）；调入仓空
        inventoryService.inbound(opening(fromWh, productId, "100", "12.50", suffix + "F"), OPERATOR);

        // 建调拨单（领域直驱，绕档案）：调出仓 → 调入仓 100
        txTemplate.executeWithoutResult(s -> transferService.create(trNo, fromWh, toWh, "整链调拨",
                List.of(new TransferLineInput(productId, new BigDecimal("100"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> transferService.approve(trNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> transferService.post(trNo, OPERATOR));

        // 过账后：调出仓 0/0.00、调入仓 100/1250.00（成本守恒）
        assertBalanceQty(fromWh, productId, "0");
        assertBalanceAmount(fromWh, productId, "0.00");
        assertBalanceQty(toWh, productId, "100");
        assertBalanceAmount(toWh, productId, "1250.00");

        long reverseAuditBefore = auditCount("stock_transfer.reverse");

        // 红冲经 AppService（@Transactional 外层事务包住库存两腿反向）
        transferAppService.reverse(trNo, OPERATOR);

        // 两仓库存双双回到调拨前：调出仓 100/1250.00、调入仓 0/0.00
        assertBalanceQty(fromWh, productId, "100");
        assertBalanceAmount(fromWh, productId, "1250.00");
        assertBalanceQty(toWh, productId, "0");
        assertBalanceAmount(toWh, productId, "0.00");
        // 调拨单 → REVERSED
        assertThat(docStatus("stock_transfer", trNo)).isEqualTo("REVERSED");
        // 反向两腿流水按原成本（反向调出腿 +1250 回调出仓；反向调入腿 −1250 从调入仓出）
        assertThat(reversalTxnCost("REVERSAL:" + trNo + ":1:OUT")).as("反向调出腿回调出仓 +1250")
                .isEqualByComparingTo("1250.00");
        assertThat(reversalTxnCost("REVERSAL:" + trNo + ":1:IN")).as("反向调入腿从调入仓出 −1250")
                .isEqualByComparingTo("-1250.00");
        // 审计
        assertThat(auditCount("stock_transfer.reverse")).isGreaterThan(reverseAuditBefore);

        // 幂等：已 REVERSED 再 reverse 被拒（领域 IllegalState 整事务回滚），库存/状态不变
        assertThatThrownBy(() -> transferAppService.reverse(trNo, OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(docStatus("stock_transfer", trNo)).isEqualTo("REVERSED");
        assertBalanceQty(fromWh, productId, "100");
        assertBalanceQty(toWh, productId, "0");

        // 对账恒等式 + 一致性（两仓两键 0 ERROR）
        assertLedgerIdentity(fromWh, productId);
        assertLedgerIdentity(toWh, productId);
        assertInventoryNoError(fromWh, productId);
        assertInventoryNoError(toWh, productId);
    }

    // =====================================================================
    // 验收②：盘点红冲——盘盈/盘亏库存回到盘点前 + 盘点单 REVERSED + 零差异行无反向流水
    // =====================================================================

    @Test
    void 盘点红冲_盘盈盘亏库存回到盘点前_零差异行无反向流水_REVERSED_审计() {
        long warehouseId = nextId();
        long productGain = nextId();   // 盘盈
        long productLoss = nextId();   // 盘亏
        long productSame = nextId();   // 无差异
        String suffix = Long.toString(System.nanoTime(), 36);
        String scNo = "SC-IR-" + suffix;

        // 期初建账
        inventoryService.inbound(opening(warehouseId, productGain, "100", "10.00", suffix + "G"), OPERATOR);
        inventoryService.inbound(opening(warehouseId, productLoss, "50", "20.00", suffix + "L"), OPERATOR);
        inventoryService.inbound(opening(warehouseId, productSame, "30", "5.00", suffix + "S"), OPERATOR);

        // 建盘点单（领域直驱，账面快照 = 期初后）+ 录入实盘（盘盈 +5、盘亏 −5、无差异）+ 审核 + 过账
        List<StockCountLineInput> lines = List.of(
                new StockCountLineInput(productGain, new BigDecimal("100"), null),
                new StockCountLineInput(productLoss, new BigDecimal("50"), null),
                new StockCountLineInput(productSame, new BigDecimal("30"), null));
        txTemplate.executeWithoutResult(s ->
                stockCountService.create(scNo, warehouseId, "整链盘点", lines, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            stockCountService.enterCount(scNo, 1, new BigDecimal("105"), OPERATOR);
            stockCountService.enterCount(scNo, 2, new BigDecimal("45"), OPERATOR);
            stockCountService.enterCount(scNo, 3, new BigDecimal("30"), OPERATOR);
        });
        txTemplate.executeWithoutResult(s -> stockCountService.approve(scNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> stockCountService.post(scNo, OPERATOR));

        // 过账后：盘盈 105、盘亏 45、无差异 30
        assertBalanceQty(warehouseId, productGain, "105");
        assertBalanceQty(warehouseId, productLoss, "45");
        assertBalanceQty(warehouseId, productSame, "30");
        // 记录盘点前余额金额（红冲后须精确回到此值）
        BigDecimal gainAmtBefore = balanceAmount(warehouseId, productGain);   // 盘点前 100@10 = 1000.00
        BigDecimal lossAmtBefore = balanceAmount(warehouseId, productLoss);

        long reverseAuditBefore = auditCount("stock_count.reverse");

        // 红冲经 AppService（@Transactional 外层事务包住库存各行反向）
        stocktakeService.reverse(scNo, OPERATOR);

        // 库存回到盘点前：盘盈 100、盘亏 50、无差异 30 不变
        assertBalanceQty(warehouseId, productGain, "100");
        assertBalanceQty(warehouseId, productLoss, "50");
        assertBalanceQty(warehouseId, productSame, "30");
        // 盘点单 → REVERSED
        assertThat(docStatus("stock_count", scNo)).isEqualTo("REVERSED");
        // 反向流水：盘盈行（出库 −5×10）、盘亏行（入库 +5×20）；无差异行无反向流水
        assertThat(reversalTxnCost("REVERSAL:" + scNo + ":1")).as("盘盈反向出库按原盘盈单价 −5×10")
                .isEqualByComparingTo("-50.00");
        assertThat(reversalTxnCost("REVERSAL:" + scNo + ":2")).as("盘亏反向入库按原盘亏成本 +5×20")
                .isEqualByComparingTo("100.00");
        assertThat(reversalTxnExists("REVERSAL:" + scNo + ":3")).as("无差异行无反向流水").isFalse();
        // 审计
        assertThat(auditCount("stock_count.reverse")).isGreaterThan(reverseAuditBefore);

        // 对账恒等式（含期初值精确归位） + 一致性 0 ERROR
        assertLedgerIdentity(warehouseId, productGain);
        assertLedgerIdentity(warehouseId, productLoss);
        assertInventoryNoError(warehouseId, productGain);
        assertInventoryNoError(warehouseId, productLoss);
        assertInventoryNoError(warehouseId, productSame);
        // 红冲后存量金额回到盘点前（盘盈出库精确无残差、盘亏入库按原成本）
        assertBalanceAmount(warehouseId, productGain, "1000.00");
        assertBalanceAmount(warehouseId, productLoss, "1000.00");
        // 上面两值即盘点前真实值（消除未使用变量告警的同时显式断言归位）
        assertThat(balanceAmount(warehouseId, productGain)).isEqualByComparingTo(gainAmtBefore);
        assertThat(balanceAmount(warehouseId, productLoss)).isEqualByComparingTo(lossAmtBefore);
    }

    // =====================================================================
    // 验收③：盘点已冲销再 reverse 被拒（领域 IllegalState 整事务回滚，库存/状态不变）
    // =====================================================================

    @Test
    void 盘点已冲销再reverse被拒_库存与状态不变() {
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String scNo = "SC-ID-" + suffix;

        inventoryService.inbound(opening(warehouseId, productId, "100", "10.00", suffix + "X"), OPERATOR);
        List<StockCountLineInput> lines = List.of(
                new StockCountLineInput(productId, new BigDecimal("100"), null));
        txTemplate.executeWithoutResult(s ->
                stockCountService.create(scNo, warehouseId, "幂等盘点", lines, OPERATOR));
        txTemplate.executeWithoutResult(s -> stockCountService.enterCount(scNo, 1, new BigDecimal("110"), OPERATOR));
        txTemplate.executeWithoutResult(s -> stockCountService.approve(scNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> stockCountService.post(scNo, OPERATOR));
        assertBalanceQty(warehouseId, productId, "110");

        stocktakeService.reverse(scNo, OPERATOR);
        assertThat(docStatus("stock_count", scNo)).isEqualTo("REVERSED");
        assertBalanceQty(warehouseId, productId, "100");

        assertThatThrownBy(() -> stocktakeService.reverse(scNo, OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(docStatus("stock_count", scNo)).isEqualTo("REVERSED");
        assertBalanceQty(warehouseId, productId, "100");
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static InboundCommand opening(long warehouseId, long productId, String quantity,
                                          String unitCost, String key) {
        return new InboundCommand(warehouseId, productId, InventoryTxnType.OPENING,
                new BigDecimal(quantity), new BigDecimal(unitCost), null,
                "OPENING", "OP-IR-" + key, 1, "OPENING:OP-IR-" + key + ":1");
    }

    private String docStatus(String table, String docNo) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE doc_no = ?",
                String.class, docNo);
    }

    private BigDecimal balanceQty(long warehouseId, long productId) {
        return jdbc.queryForObject("SELECT quantity FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                BigDecimal.class, warehouseId, productId);
    }

    private void assertBalanceQty(long warehouseId, long productId, String expected) {
        assertThat(balanceQty(warehouseId, productId)).as("仓 %d 商品 %d 余额数量", warehouseId, productId)
                .isEqualByComparingTo(expected);
    }

    private BigDecimal balanceAmount(long warehouseId, long productId) {
        return jdbc.queryForObject("SELECT cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                BigDecimal.class, warehouseId, productId);
    }

    private void assertBalanceAmount(long warehouseId, long productId, String expected) {
        assertThat(balanceAmount(warehouseId, productId)).as("仓 %d 商品 %d 余额金额", warehouseId, productId)
                .isEqualByComparingTo(expected);
    }

    /** 按幂等键取反向流水 total_cost。 */
    private BigDecimal reversalTxnCost(String idempotencyKey) {
        return jdbc.queryForObject("SELECT total_cost FROM inventory_transaction "
                + "WHERE tenant_id = 0 AND idempotency_key = ?", BigDecimal.class, idempotencyKey);
    }

    private boolean reversalTxnExists(String idempotencyKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction "
                + "WHERE tenant_id = 0 AND idempotency_key = ?", Long.class, idempotencyKey);
        return count != null && count > 0;
    }

    /** 对账恒等式：Σ流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额。 */
    private void assertLedgerIdentity(long warehouseId, long productId) {
        var sums = jdbc.queryForMap("SELECT SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum "
                        + "FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                warehouseId, productId);
        var balance = jdbc.queryForMap("SELECT quantity, cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        assertThat((BigDecimal) sums.get("qty_sum")).as("仓 %d 商品 %d Σ流水数量 = 余额数量", warehouseId, productId)
                .isEqualByComparingTo((BigDecimal) balance.get("quantity"));
        assertThat((BigDecimal) sums.get("cost_sum")).as("仓 %d 商品 %d Σ流水金额 = 余额金额", warehouseId, productId)
                .isEqualByComparingTo((BigDecimal) balance.get("cost_amount"));
    }

    /** 一致性 check()：该仓该商品库存键 0 ERROR（账实勾稽 Σ流水=余额）。 */
    private void assertInventoryNoError(long warehouseId, long productId) {
        ConsistencyReport report = consistencyCheckService.check();
        String invKey = "warehouse=" + warehouseId + ",product=" + productId;
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> b.key() != null && b.key().contains(invKey))
                .toList();
        assertThat(errors).as("仓 %d 商品 %d 红冲后应 0 ERROR，实际：%s", warehouseId, productId, errors)
                .isEmpty();
    }

    private long auditCount(String action) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?",
                Long.class, action);
        return c == null ? 0L : c;
    }
}
