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
import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.inventory.JdbcStockChecker;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLineInput;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLineInput;

/**
 * 报工与完工入库端到端链路集成测试（M5-T05 设计真源 §6 验收）。
 *
 * <p>使用 Testcontainers 真实 MySQL + Flyway 迁移，完整装配
 * {@link AuditConfig} + {@link InventoryInfraConfig} + {@link CatalogInfraConfig}
 * + {@link ProductionInfraConfig}，驱动链路：
 * <ol>
 *   <li>PURCHASE_IN 预置子件库存（用 TransactionalInventoryService.inbound）；</li>
 *   <li>手工建工单（createManual）→ release → start（→ EXECUTING）；</li>
 *   <li>建领料单 → approve → post（PRODUCTION_ISSUE 出库，issuedCost 回填）；</li>
 *   <li>建报工单 → approve → post（PRODUCTION_IN 完工入库，inboundCost = issuedCost）；</li>
 *   <li>断言：PRODUCTION_IN 流水存在、inboundCost > 0、PR status=COMPLETED、
 *       WO.completedQty 更新、成品库存余额正确；</li>
 *   <li>零发料成本路径：无已过账领料单时 post 抛 IAE "issuedCost"，状态不变；</li>
 *   <li>一致性校验 0 ERROR。</li>
 * </ol>
 *
 * <p>默认不执行：{@code @Tag("integration-db")} 被 surefire excludedGroups 排除。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class ProductionReportFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-report";

    /** 独立 ID 生成器（避免与同 DB 的其他链路测试碰撞） */
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static TransactionalWorkOrderService workOrderService;
    private static MaterialIssueAppService materialIssueAppService;
    private static ProductionReportAppService productionReportAppService;
    private static ConsistencyCheckService consistencyCheckService;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
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
        productionReportAppService = context.getBean(ProductionReportAppService.class);
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
         * 故须显式提供（照 MaterialIssueFlowIntegrationTest 范式）。
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
    void 报工过账_完工入库_库存成本正确_工单完工数量更新_一致性0ERROR() {
        String suffix = Long.toString(System.nanoTime(), 36);
        long warehouseId  = nextId();   // 领料/完工共用仓库
        long componentId  = nextId();   // 子件（原材料）
        long finishedId   = nextId();   // 成品（报工产出物）
        long unitId       = nextId();   // 计量单位（无 FK 约束，任意 id）

        // ---- 1. PURCHASE_IN 预置子件库存：80 件 @10.00，金额 800.00 ----
        String inboundKey = "PURCH-IN-RPT:" + suffix + ":1";
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(
                        new InboundCommand(warehouseId, componentId,
                                InventoryTxnType.PURCHASE_IN,
                                new BigDecimal("80"), new BigDecimal("10.00"),
                                null, "PURCHASE_RECEIPT", "PR-RPT-" + suffix, 1, inboundKey),
                        OPERATOR));

        assertBalanceQty(warehouseId, componentId, "80");
        assertBalanceAmount(warehouseId, componentId, "800.00");

        // ---- 2. 建工单 → release → start（→ EXECUTING） ----
        // warehouseId 传入工单头（指定完工入库仓）
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(
                        finishedId, new BigDecimal("10"), unitId,
                        null, null, warehouseId,
                        LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30),
                        "报工集成测试工单", OPERATOR));
        String woDocNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        assertThat(woDocNo).startsWith("WO-");

        txTemplate.executeWithoutResult(s -> workOrderService.release(woDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(woDocNo, OPERATOR));

        DocumentStatus woStatus = DocumentStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM work_order WHERE doc_no = ? AND tenant_id = 0",
                String.class, woDocNo));
        assertThat(woStatus).isEqualTo(DocumentStatus.EXECUTING);

        // ---- 3. 建领料单 → approve → post（领 50 件子件，出库 500.00） ----
        List<MaterialIssueLineInput> miLines = List.of(
                new MaterialIssueLineInput(componentId,
                        new BigDecimal("50"), new BigDecimal("50"), unitId));
        MaterialIssue mi = txTemplate.execute(s ->
                materialIssueAppService.create(woDocNo, warehouseId, "报工测试领料", miLines, OPERATOR));
        assertThat(mi).isNotNull();
        String miDocNo = mi.getDocNo();
        assertThat(miDocNo).startsWith("MI-");

        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(miDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.post(miDocNo, OPERATOR));

        // 断言：子件库存降至 30，金额 300.00
        assertBalanceQty(warehouseId, componentId, "30");
        assertBalanceAmount(warehouseId, componentId, "300.00");

        MaterialIssue miPosted = txTemplate.execute(s -> materialIssueAppService.get(miDocNo));
        assertThat(miPosted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(miPosted.totalIssuedCost()).isEqualByComparingTo("500.00");

        // ---- 4. 建报工单（草稿）→ approve → post（完工入库 5 件，inboundCost = 500.00） ----
        List<ProductionReportLineInput> prLines = List.of(
                new ProductionReportLineInput(
                        null, null, null,
                        new BigDecimal("2.500000"), null, unitId));
        ProductionReport pr = txTemplate.execute(s ->
                productionReportAppService.create(
                        woDocNo, warehouseId, finishedId,
                        new BigDecimal("5"), null, unitId, "报工集成测试",
                        prLines, OPERATOR));
        assertThat(pr).isNotNull();
        String prDocNo = pr.getDocNo();
        assertThat(prDocNo).startsWith("PR-");
        assertThat(pr.getStatus()).isEqualTo(DocumentStatus.DRAFT);

        txTemplate.executeWithoutResult(s -> productionReportAppService.approve(prDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.post(prDocNo, OPERATOR));

        // ---- 5. 断言：报工单 COMPLETED，inboundCost 回填 ----
        ProductionReport prPosted = txTemplate.execute(s -> productionReportAppService.get(prDocNo));
        assertThat(prPosted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        // inboundCost = 全部已过账领料成本 500.00（完工 5 件，单位成本 100.00/件）
        assertThat(prPosted.getInboundCost()).isNotNull();
        assertThat(prPosted.getInboundCost()).isEqualByComparingTo("500.00");

        // ---- 6. 断言：成品库存完工入库（PRODUCTION_IN 流水存在，余额 = 500.00） ----
        int productionInCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND txn_type = 'PRODUCTION_IN'",
                Integer.class, warehouseId, finishedId);
        assertThat(productionInCount).isEqualTo(1);

        // 成品库存：5 件，成本总额 500.00
        assertBalanceQty(warehouseId, finishedId, "5");
        assertBalanceAmount(warehouseId, finishedId, "500.00");

        // ---- 7. 断言：工单 completedQty 已更新（+5） ----
        BigDecimal woCompletedQty = jdbc.queryForObject(
                "SELECT completed_qty FROM work_order WHERE doc_no = ? AND tenant_id = 0",
                BigDecimal.class, woDocNo);
        assertThat(woCompletedQty)
                .as("工单 completed_qty 应累加本次完工数量 5")
                .isEqualByComparingTo("5");

        // ---- 8. 一致性校验：子件 + 成品维度均无 ERROR ----
        ConsistencyReport report = consistencyCheckService.check();
        String componentKey = "warehouse=" + warehouseId + ",product=" + componentId;
        String finishedKey  = "warehouse=" + warehouseId + ",product=" + finishedId;
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> b.key() != null
                        && (b.key().contains(componentKey) || b.key().contains(finishedKey)))
                .toList();
        assertThat(errors)
                .as("完工入库链路完成后相关库存维度应 0 个 ERROR break，实际: %s", errors)
                .isEmpty();
    }

    // ======================================================================== 零发料成本路径

    @Test
    void 无已过账领料成本_报工过账_抛IAE_单据状态不前进() {
        String suffix = Long.toString(System.nanoTime(), 36) + "Z";
        long warehouseId = nextId();
        long finishedId  = nextId();
        long unitId      = nextId();

        // 建工单 → release → start（不预置库存、不建领料单）
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(
                        finishedId, new BigDecimal("10"), unitId,
                        null, null, warehouseId,
                        LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30),
                        "零成本测试工单", OPERATOR));
        String woDocNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        txTemplate.executeWithoutResult(s -> workOrderService.release(woDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(woDocNo, OPERATOR));

        // 建报工单 → approve（至 APPROVED）
        List<ProductionReportLineInput> prLines = List.of(
                new ProductionReportLineInput(
                        null, null, null,
                        new BigDecimal("1.000000"), null, unitId));
        ProductionReport pr = txTemplate.execute(s ->
                productionReportAppService.create(
                        woDocNo, warehouseId, finishedId,
                        new BigDecimal("5"), null, unitId, "零成本测试",
                        prLines, OPERATOR));
        String prDocNo = pr.getDocNo();
        txTemplate.executeWithoutResult(s -> productionReportAppService.approve(prDocNo, OPERATOR));

        // post 时无已过账领料成本（issuedCost = 0），应抛 IAE 含 "issuedCost"，整批回滚
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s ->
                        productionReportAppService.post(prDocNo, OPERATOR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuedCost");

        // 报工单状态回退到回滚前的 APPROVED（未能前进到 COMPLETED）
        String statusStr = jdbc.queryForObject(
                "SELECT status FROM production_report WHERE doc_no = ? AND tenant_id = 0",
                String.class, prDocNo);
        assertThat(statusStr).isEqualTo("APPROVED");

        // 无 PRODUCTION_IN 流水产生
        int productionInCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND txn_type = 'PRODUCTION_IN'",
                Integer.class, warehouseId, finishedId);
        assertThat(productionInCount).isEqualTo(0);
    }

    // ======================================================================== 多次报工累计

    @Test
    void 同一工单多次报工_completedQty累计() {
        String suffix = Long.toString(System.nanoTime(), 36) + "M";
        long warehouseId = nextId();
        long componentId = nextId();
        long finishedId  = nextId();
        long unitId      = nextId();

        // 预置 200 件子件
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(
                        new InboundCommand(warehouseId, componentId,
                                InventoryTxnType.PURCHASE_IN,
                                new BigDecimal("200"), new BigDecimal("5.00"),
                                null, "PURCHASE_RECEIPT", "PR-MULTI-" + suffix, 1,
                                "PURCH-IN-MULTI:" + suffix + ":1"),
                        OPERATOR));

        // 建工单 → release → start
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(
                        finishedId, new BigDecimal("20"), unitId,
                        null, null, warehouseId,
                        LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30),
                        "多次报工工单", OPERATOR));
        String woDocNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        txTemplate.executeWithoutResult(s -> workOrderService.release(woDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(woDocNo, OPERATOR));

        // 第一次领料（60 件，成本 300.00）→ 过账
        MaterialIssue mi1 = txTemplate.execute(s ->
                materialIssueAppService.create(woDocNo, warehouseId, "首次领料",
                        List.of(new MaterialIssueLineInput(componentId,
                                new BigDecimal("60"), new BigDecimal("60"), unitId)), OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(mi1.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.post(mi1.getDocNo(), OPERATOR));

        // 第一次报工：完工 3 件
        ProductionReport pr1 = txTemplate.execute(s ->
                productionReportAppService.create(
                        woDocNo, warehouseId, finishedId,
                        new BigDecimal("3"), null, unitId, "首次报工",
                        List.of(new ProductionReportLineInput(null, null, null,
                                new BigDecimal("1.000000"), null, unitId)), OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.approve(pr1.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.post(pr1.getDocNo(), OPERATOR));

        BigDecimal completedAfterFirst = jdbc.queryForObject(
                "SELECT completed_qty FROM work_order WHERE doc_no = ? AND tenant_id = 0",
                BigDecimal.class, woDocNo);
        assertThat(completedAfterFirst).isEqualByComparingTo("3");

        // 第二次领料（40 件，成本 200.00）→ 过账
        MaterialIssue mi2 = txTemplate.execute(s ->
                materialIssueAppService.create(woDocNo, warehouseId, "第二次领料",
                        List.of(new MaterialIssueLineInput(componentId,
                                new BigDecimal("40"), new BigDecimal("40"), unitId)), OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(mi2.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.post(mi2.getDocNo(), OPERATOR));

        // 第二次报工：完工 4 件
        ProductionReport pr2 = txTemplate.execute(s ->
                productionReportAppService.create(
                        woDocNo, warehouseId, finishedId,
                        new BigDecimal("4"), null, unitId, "第二次报工",
                        List.of(new ProductionReportLineInput(null, null, null,
                                new BigDecimal("1.000000"), null, unitId)), OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.approve(pr2.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.post(pr2.getDocNo(), OPERATOR));

        // 工单 completedQty 应累积为 3 + 4 = 7
        BigDecimal completedAfterSecond = jdbc.queryForObject(
                "SELECT completed_qty FROM work_order WHERE doc_no = ? AND tenant_id = 0",
                BigDecimal.class, woDocNo);
        assertThat(completedAfterSecond)
                .as("两次报工后 completedQty 应累积为 7")
                .isEqualByComparingTo("7");

        // 成品库存应为 3 + 4 = 7 件（两次 PRODUCTION_IN）
        assertBalanceQty(warehouseId, finishedId, "7");

        // 评审 P0 守门：分批完工料费不重复入账——
        // pr1 入 300（增量 300），pr2 入 200（增量 500-300=200），各自 inbound_cost 精确：
        BigDecimal pr1Cost = jdbc.queryForObject(
                "SELECT inbound_cost FROM production_report WHERE doc_no = ? AND tenant_id = 0",
                BigDecimal.class, pr1.getDocNo());
        assertThat(pr1Cost).as("pr1 完工入库成本=增量 300").isEqualByComparingTo("300.00");
        BigDecimal pr2Cost = jdbc.queryForObject(
                "SELECT inbound_cost FROM production_report WHERE doc_no = ? AND tenant_id = 0",
                BigDecimal.class, pr2.getDocNo());
        assertThat(pr2Cost).as("pr2 完工入库成本=增量 200，不含重复计入的 mi1 料费").isEqualByComparingTo("200.00");
        // Σ完工入库金额 (成品 cost_amount) ≡ Σ领料出库金额 (300+200=500)，料的进出守恒（设计真源 R1）
        assertBalanceAmount(warehouseId, finishedId, "500.00");

        // PRODUCTION_IN 流水应有 2 条
        int productionInCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND txn_type = 'PRODUCTION_IN'",
                Integer.class, warehouseId, finishedId);
        assertThat(productionInCount).isEqualTo(2);
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
