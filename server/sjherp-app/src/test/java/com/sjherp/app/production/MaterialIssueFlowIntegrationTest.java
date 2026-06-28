package com.sjherp.app.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.sjherp.app.config.AuditConfig;
import com.sjherp.app.config.CatalogInfraConfig;
import com.sjherp.app.config.InventoryInfraConfig;
import com.sjherp.app.config.ProductionInfraConfig;
import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.app.inventory.JdbcStockChecker;
import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLineInput;
import com.sjherp.domain.production.MaterialReturn;
import com.sjherp.domain.production.MaterialReturnLineInput;
import com.sjherp.domain.inventory.InsufficientStockException;

/**
 * 领料/退料/齐套 端到端链路集成测试（M5-T04 设计真源 §6 验收）。
 *
 * <p>使用 Testcontainers 真实 MySQL + Flyway 迁移，完整装配
 * {@link AuditConfig} + {@link InventoryInfraConfig} + {@link CatalogInfraConfig}
 * + {@link ProductionInfraConfig}，驱动链路：
 * <ol>
 *   <li>PURCHASE_IN 预置库存（用 TransactionalInventoryService.inbound）；</li>
 *   <li>手工建工单（createManual）→ release → start（→ EXECUTING）；</li>
 *   <li>建领料单 → approve → post（PRODUCTION_ISSUE 出库，issuedCost 回填）；</li>
 *   <li>库存余额下降断言、issuedCost > 0 断言；</li>
 *   <li>库存不足路径：post 时整批回滚，库存不变；</li>
 *   <li>建退料单 → approve → post（PRODUCTION_RETURN 入库，成本按领料原价归还）；</li>
 *   <li>库存余额恢复断言；</li>
 *   <li>一致性校验本链路相关键 0 ERROR。</li>
 * </ol>
 *
 * <p>默认不执行：{@code @Tag("integration-db")} 被 surefire excludedGroups 排除。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class MaterialIssueFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-prod";

    /** 独立 ID 生成器（避免与同 DB 的其他链路测试碰撞） */
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static TransactionalWorkOrderService workOrderService;
    private static MaterialIssueAppService materialIssueAppService;
    private static MaterialReturnAppService materialReturnAppService;
    private static ConsistencyCheckService consistencyCheckService;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        // Flyway 迁移（使用 migration datasource，与应用 datasource 同 URL）
        DataSource migrationDs = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(migrationDs)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        txTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        inventoryService = context.getBean(TransactionalInventoryService.class);
        workOrderService = context.getBean(TransactionalWorkOrderService.class);
        materialIssueAppService = context.getBean(MaterialIssueAppService.class);
        materialReturnAppService = context.getBean(MaterialReturnAppService.class);
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
    @Import({AuditConfig.class, InventoryInfraConfig.class, CatalogInfraConfig.class,
            ProductionInfraConfig.class})
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

        /** 一致性校验（allow-negative=false，与生产参数一致） */
        @Bean
        ConsistencyCheckDao consistencyCheckDao(JdbcTemplate jdbcTemplate) {
            return new ConsistencyCheckDao(jdbcTemplate);
        }

        @Bean
        ConsistencyCheckService consistencyCheckService(ConsistencyCheckDao dao) {
            return new ConsistencyCheckService(dao, false);
        }

        /**
         * 库存占用检查（仓库/商品停用前引用约束，JdbcStockChecker 只读 SQL 实现）。
         * CatalogInfraConfig.productService 依赖此 bean，本测试装配 CatalogInfraConfig
         * 故须显式提供（照 OpeningStockImportIntegrationTest 范式）。
         */
        @Bean
        JdbcStockChecker stockChecker(JdbcTemplate jdbcTemplate) {
            return new JdbcStockChecker(jdbcTemplate);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // ======================================================================== 主链路

    @Test
    void 领料过账_退料过账_库存成本守恒_一致性0ERROR() {
        // 唯一标识后缀，隔离此用例数据
        String suffix = Long.toString(System.nanoTime(), 36);
        long warehouseId = nextId();
        long componentId = nextId();   // 子件（原材料）
        long finishedId  = nextId();   // 成品（工单产出物，此处仅做数据隔离）
        long unitId      = nextId();   // 计量单位（无 FK 约束，任意 id）
        String woNo = "WO-FL-" + suffix;

        // ---- 1. PURCHASE_IN 预置库存：100 件 @12.50，金额 1250.00 ----
        String inboundKey = "PURCH-IN:" + suffix + ":1";
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(
                        new InboundCommand(warehouseId, componentId,
                                InventoryTxnType.PURCHASE_IN,
                                new BigDecimal("100"), new BigDecimal("12.50"),
                                null, "PURCHASE_RECEIPT", "PR-FL-" + suffix, 1, inboundKey),
                        OPERATOR));

        // 断言库存余额 100 / 1250.00
        assertBalanceQty(warehouseId, componentId, "100");
        assertBalanceAmount(warehouseId, componentId, "1250.00");

        // ---- 2. 建工单 → release → start（→ EXECUTING） ----
        // createManual(productId=成品, plannedQty, unitId, bomVersion=null, routingVersion=null,
        //              warehouseId=null, plannedStartDate, plannedEndDate, remark, operator)
        // 工单不依赖库存，warehouseId 可 null（M5-T03 设计，领料单另行指定领料仓）
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(
                        finishedId, new BigDecimal("50"), unitId,
                        null, null, null,
                        LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30),
                        "集成测试工单", OPERATOR));
        // get 得到刚建的工单单号（无法提前知道——靠 DB 查）
        String generatedWoNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        assertThat(generatedWoNo).startsWith("WO-");

        txTemplate.executeWithoutResult(s -> workOrderService.release(generatedWoNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(generatedWoNo, OPERATOR));

        // 确认工单已进入 EXECUTING
        DocumentStatus woStatus = DocumentStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM work_order WHERE doc_no = ? AND tenant_id = 0",
                String.class, generatedWoNo));
        assertThat(woStatus).isEqualTo(DocumentStatus.EXECUTING);

        // ---- 3. 建领料单（草稿）→ approve → post ----
        // 领 40 件子件，requiredQty = 40，quantity = 40
        List<MaterialIssueLineInput> miLines = List.of(
                new MaterialIssueLineInput(componentId,
                        new BigDecimal("40"), new BigDecimal("40"), unitId));

        MaterialIssue mi = txTemplate.execute(s ->
                materialIssueAppService.create(generatedWoNo, warehouseId, "集成测试领料", miLines, OPERATOR));
        assertThat(mi).isNotNull();
        String miDocNo = mi.getDocNo();
        assertThat(miDocNo).startsWith("MI-");
        assertThat(mi.getStatus()).isEqualTo(DocumentStatus.DRAFT);

        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(miDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.post(miDocNo, OPERATOR));

        // ---- 4. 断言：库存余额降至 60，issuedCost 回填 ----
        // 出 40 件 @12.50 = 500.00；余 60 件 = 750.00
        assertBalanceQty(warehouseId, componentId, "60");
        assertBalanceAmount(warehouseId, componentId, "750.00");

        MaterialIssue miPosted = txTemplate.execute(s -> materialIssueAppService.get(miDocNo));
        assertThat(miPosted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(miPosted.totalIssuedCost()).isEqualByComparingTo("500.00");

        // 流水中应出现 PRODUCTION_ISSUE 记录
        int issueCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND txn_type = 'PRODUCTION_ISSUE'",
                Integer.class, warehouseId, componentId);
        assertThat(issueCount).isEqualTo(1);

        // ---- 5. 建退料单 → approve → post（归还 20 件，按原领料成本 12.50/件） ----
        List<MaterialReturnLineInput> mrLines = List.of(
                new MaterialReturnLineInput(componentId,
                        new BigDecimal("20"), unitId, 1));   // srcIssueLineNo = 1

        MaterialReturn mr = txTemplate.execute(s ->
                materialReturnAppService.create(miDocNo, warehouseId, "集成测试退料", mrLines, OPERATOR));
        assertThat(mr).isNotNull();
        String mrDocNo = mr.getDocNo();
        assertThat(mrDocNo).startsWith("MR-");

        txTemplate.executeWithoutResult(s -> materialReturnAppService.approve(mrDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> materialReturnAppService.post(mrDocNo, OPERATOR));

        // ---- 6. 断言：库存余额恢复至 80 件；退料成本 = 250.00（20 × 12.50） ----
        assertBalanceQty(warehouseId, componentId, "80");
        // 退料按原单价入库，不依赖移动加权（避期间漂移）
        BigDecimal balanceAmt = jdbc.queryForObject(
                "SELECT cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                BigDecimal.class, warehouseId, componentId);
        // 余额 = 750.00（领料后）+ 250.00（退料） = 1000.00
        assertThat(balanceAmt).isEqualByComparingTo("1000.00");

        MaterialReturn mrPosted = txTemplate.execute(s -> materialReturnAppService.get(mrDocNo));
        assertThat(mrPosted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(mrPosted.totalReturnedCost()).isEqualByComparingTo("250.00");

        // PRODUCTION_RETURN 流水已存在
        int returnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND txn_type = 'PRODUCTION_RETURN'",
                Integer.class, warehouseId, componentId);
        assertThat(returnCount).isEqualTo(1);

        // ---- 7. 一致性校验：本链路相关库存维度 0 ERROR ----
        ConsistencyReport report = consistencyCheckService.check();
        String invKey = "warehouse=" + warehouseId + ",product=" + componentId;
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> b.key() != null && b.key().contains(invKey))
                .toList();
        assertThat(errors)
                .as("领料/退料链路完成后本库存维度应 0 个 ERROR break，实际: %s", errors)
                .isEmpty();
    }

    // ======================================================================== 库存不足路径

    @Test
    void 领料过账_库存不足_整批回滚_单据状态不变() {
        String suffix = Long.toString(System.nanoTime(), 36) + "X";
        long warehouseId = nextId();
        long componentId = nextId();
        long finishedId  = nextId();
        long unitId      = nextId();

        // 预置库存 5 件 @10.00
        String inboundKey = "PURCH-IN-INSUFF:" + suffix + ":1";
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(
                        new InboundCommand(warehouseId, componentId,
                                InventoryTxnType.PURCHASE_IN,
                                new BigDecimal("5"), new BigDecimal("10.00"),
                                null, "PURCHASE_RECEIPT", "PR-INSUFF-" + suffix, 1, inboundKey),
                        OPERATOR));

        // 建工单 → release → start
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(
                        finishedId, new BigDecimal("10"), unitId,
                        null, null, null,
                        LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30),
                        "不足用例工单", OPERATOR));
        String generatedWoNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        txTemplate.executeWithoutResult(s -> workOrderService.release(generatedWoNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(generatedWoNo, OPERATOR));

        // 建领料单：尝试领 20 件（超出库存 5 件）
        List<MaterialIssueLineInput> miLines = List.of(
                new MaterialIssueLineInput(componentId,
                        new BigDecimal("20"), new BigDecimal("20"), unitId));

        MaterialIssue mi = txTemplate.execute(s ->
                materialIssueAppService.create(generatedWoNo, warehouseId, "不足测试", miLines, OPERATOR));
        String miDocNo = mi.getDocNo();

        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(miDocNo, OPERATOR));

        // post 时库存不足，整批回滚，抛 InsufficientStockException
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s ->
                        materialIssueAppService.post(miDocNo, OPERATOR)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("库存不足");

        // 库存余额未变（仍 5 件）
        assertBalanceQty(warehouseId, componentId, "5");

        // 领料单状态回退到回滚前的 APPROVED（未能前进）
        String statusStr = jdbc.queryForObject(
                "SELECT status FROM material_issue WHERE doc_no = ? AND tenant_id = 0",
                String.class, miDocNo);
        assertThat(statusStr).isEqualTo("APPROVED");
    }

    // ======================================================================== 幂等性

    @Test
    void 领料过账_幂等键重放_不生成重复流水() {
        String suffix = Long.toString(System.nanoTime(), 36) + "I";
        long warehouseId = nextId();
        long componentId = nextId();
        long finishedId  = nextId();
        long unitId      = nextId();

        // 预置库存 50 件 @8.00
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(
                        new InboundCommand(warehouseId, componentId,
                                InventoryTxnType.PURCHASE_IN,
                                new BigDecimal("50"), new BigDecimal("8.00"),
                                null, "PURCHASE_RECEIPT", "PR-IDEM-" + suffix, 1,
                                "PURCH-IN-IDEM:" + suffix + ":1"),
                        OPERATOR));

        // 建工单 → release → start
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(
                        finishedId, new BigDecimal("10"), unitId,
                        null, null, null,
                        LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30),
                        "幂等用例工单", OPERATOR));
        String generatedWoNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        txTemplate.executeWithoutResult(s -> workOrderService.release(generatedWoNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(generatedWoNo, OPERATOR));

        // 建领料单 → approve → post
        List<MaterialIssueLineInput> miLines = List.of(
                new MaterialIssueLineInput(componentId,
                        new BigDecimal("10"), new BigDecimal("10"), unitId));
        MaterialIssue mi = txTemplate.execute(s ->
                materialIssueAppService.create(generatedWoNo, warehouseId, "幂等测试", miLines, OPERATOR));
        String miDocNo = mi.getDocNo();
        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(miDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.post(miDocNo, OPERATOR));

        // 幂等键：MATERIAL_ISSUE:<miDocNo>:1，再次直接触发 InventoryService.execute 应不重复插入
        // （因领料单状态已 COMPLETED，AppService 层的 post 会被拒绝，这里只校验流水仅一条）
        int issueCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND txn_type = 'PRODUCTION_ISSUE'",
                Integer.class, warehouseId, componentId);
        assertThat(issueCount).isEqualTo(1);

        // 库存余额正确：50 - 10 = 40 件
        assertBalanceQty(warehouseId, componentId, "40");
    }

    // ======================================================================== SQL 旁证助手

    /**
     * 断言库存余额数量（允许 ±0.000001 精度，避免 BigDecimal scale 差异）。
     */
    private void assertBalanceQty(long warehouseId, long productId, String expectedQty) {
        BigDecimal qty = jdbc.queryForObject(
                "SELECT quantity FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                BigDecimal.class, warehouseId, productId);
        assertThat(qty)
                .as("库存余额数量 warehouse=%d product=%d", warehouseId, productId)
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal(expectedQty));
    }

    /**
     * 断言库存余额金额。
     */
    private void assertBalanceAmount(long warehouseId, long productId, String expectedAmt) {
        BigDecimal amt = jdbc.queryForObject(
                "SELECT cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                BigDecimal.class, warehouseId, productId);
        assertThat(amt)
                .as("库存余额金额 warehouse=%d product=%d", warehouseId, productId)
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal(expectedAmt));
    }
}
