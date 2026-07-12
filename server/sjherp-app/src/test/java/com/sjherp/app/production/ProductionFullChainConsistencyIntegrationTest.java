package com.sjherp.app.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.app.config.AuditConfig;
import com.sjherp.app.config.CatalogInfraConfig;
import com.sjherp.app.config.GlInfraConfig;
import com.sjherp.app.config.InventoryInfraConfig;
import com.sjherp.app.config.ProductionCostInfraConfig;
import com.sjherp.app.config.ProductionInfraConfig;
import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyCheckType;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.inventory.JdbcStockChecker;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLineInput;
import com.sjherp.domain.production.MaterialReturn;
import com.sjherp.domain.production.MaterialReturnLineInput;
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLineInput;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLineInput;

/**
 * 生产链全链路一致性勾稽端到端验收（M5-T08 设计真源 §3，M5 生产模块里程碑出口条件）。
 *
 * <p>Testcontainers 真实 MySQL + Flyway 全量迁移，生产同套装配，驱动完整生产链：
 * PURCHASE_IN 预置子件 → 工单 → 下达 → 开工 → 领料过账（PRODUCTION_ISSUE）
 * → 报工完工过账（PRODUCTION_IN）→ 月末成本结转过账（COST_ADJUST）。
 *
 * <p>断言（含本批新规则 12–16）：
 * <ol>
 *   <li><b>核心里程碑</b>：{@link ConsistencyCheckService#check()} 返回 0 ERROR break；</li>
 *   <li>规则12：领料行 issued_cost = −Σ PRODUCTION_ISSUE 流水金额；</li>
 *   <li>规则13：报工 inbound_cost = Σ PRODUCTION_IN 流水金额；</li>
 *   <li>规则14：Σ完工入库料 = Σ净领料（无退料时相等，料费守恒 R1）；</li>
 *   <li>规则15：工单 completed_qty = Σ报工 completed_qty；</li>
 *   <li>规则16：成本结转工费增量 = Σ COST_ADJUST 流水金额。</li>
 * </ol>
 */
@Tag("integration-db")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionFullChainConsistencyIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-xcheck";
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static TransactionalWorkOrderService workOrderService;
    private static MaterialIssueAppService materialIssueAppService;
    private static MaterialReturnAppService materialReturnAppService;
    private static ProductionReportAppService productionReportAppService;
    private static ProductionCostSettlementAppService costSettlementAppService;
    private static ConsistencyCheckService consistencyCheckService;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        DataSource migrationDs = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(migrationDs).locations("classpath:db/migration").load().migrate();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        txTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        inventoryService = context.getBean(TransactionalInventoryService.class);
        workOrderService = context.getBean(TransactionalWorkOrderService.class);
        materialIssueAppService = context.getBean(MaterialIssueAppService.class);
        materialReturnAppService = context.getBean(MaterialReturnAppService.class);
        productionReportAppService = context.getBean(ProductionReportAppService.class);
        costSettlementAppService = context.getBean(ProductionCostSettlementAppService.class);
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
            GlInfraConfig.class, ProductionInfraConfig.class, ProductionCostInfraConfig.class})
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
        ConsistencyCheckDao consistencyCheckDao(JdbcTemplate jdbcTemplate) {
            return new ConsistencyCheckDao(jdbcTemplate);
        }

        @Bean
        ConsistencyCheckService consistencyCheckService(ConsistencyCheckDao dao) {
            return new ConsistencyCheckService(dao, false);
        }

        /** CatalogInfraConfig.productService 依赖（照 ProductionCostSettlementFlowIntegrationTest）。 */
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
    @Order(1)
    void 生产链全链路勾稽_0ERROR_规则12到16各旁证相等() {
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        String period = ym.format(DateTimeFormatter.ofPattern("yyyyMM"));
        LocalDate bizDate = ym.atDay(Math.min(15, ym.lengthOfMonth()));

        long warehouseId = nextId();
        long componentId = nextId();
        long finishedId = nextId();
        long unitId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);

        // 开账期 + 成本参数（人工费率 20、制造费用率 5）
        txTemplate.executeWithoutResult(s ->
                context.getBean(com.sjherp.domain.gl.AccountingPeriodService.class).open(period, OPERATOR));
        jdbc.update("INSERT INTO production_cost_param "
                + "(tenant_id, period, default_labor_rate, overhead_rate, created_by, created_at, "
                + "updated_by, updated_at) VALUES (0, ?, 20, 5, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))",
                period, OPERATOR, OPERATOR);
        seedProducts(unitId, componentId, finishedId, suffix);

        // 1. PURCHASE_IN 预置子件库存：100 件 @10.00
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(new InboundCommand(warehouseId, componentId,
                        InventoryTxnType.PURCHASE_IN, new BigDecimal("100"), new BigDecimal("10.00"),
                        null, "PURCHASE_RECEIPT", "PR-XC-" + suffix, 1, "PURCH-IN-XC:" + suffix + ":1"),
                        OPERATOR));

        // 2. 建工单（计划 10）→ release → start（EXECUTING）
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(finishedId, new BigDecimal("10"), unitId,
                        null, null, warehouseId, bizDate, bizDate, "全链路勾稽工单", OPERATOR));
        String woDocNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        txTemplate.executeWithoutResult(s -> workOrderService.release(woDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(woDocNo, OPERATOR));

        // 3. 领料 80 件子件（出库 800.00）→ 过账
        List<MaterialIssueLineInput> miLines = List.of(
                new MaterialIssueLineInput(componentId, new BigDecimal("80"), new BigDecimal("80"), unitId));
        MaterialIssue mi = txTemplate.execute(s ->
                materialIssueAppService.create(woDocNo, warehouseId, "领料", miLines, OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(mi.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.post(mi.getDocNo(), OPERATOR));

        // 4. 报工 8 件完工（料 800 入产成品），工时 10 小时
        List<ProductionReportLineInput> prLines = List.of(
                new ProductionReportLineInput(1, "工序1", null, new BigDecimal("10.000000"), null, unitId));
        ProductionReport pr = txTemplate.execute(s ->
                productionReportAppService.create(woDocNo, warehouseId, finishedId,
                        new BigDecimal("8"), null, unitId, "报工", prLines, OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.approve(pr.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.post(pr.getDocNo(), OPERATOR));

        // 5. 月末成本结转：完工 8、在产 2 @50%
        ProductionCostSettlement cs = txTemplate.execute(s ->
                costSettlementAppService.create(period, "月末结转",
                        List.of(new ProductionCostSettlementLineInput(woDocNo,
                                new BigDecimal("2"), new BigDecimal("50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> costSettlementAppService.approve(cs.getDocNo(), OPERATOR));
        ProductionCostSettlement posted = txTemplate.execute(s ->
                costSettlementAppService.post(cs.getDocNo(), OPERATOR));
        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        String miDocNo = mi.getDocNo();
        String prDocNo = pr.getDocNo();
        String pcDocNo = posted.getDocNo();

        // ===== 断言①（核心里程碑）：全库 0 ERROR break（含本批规则 12–16） =====
        ConsistencyReport report = consistencyCheckService.check();
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .toList();
        assertThat(errors).as("生产链全链路应 0 ERROR break，实际: %s", errors).isEmpty();

        // ===== 断言②（规则12 领料）：issued_cost = −Σ PRODUCTION_ISSUE 流水金额 =====
        BigDecimal issuedCost = jdbc.queryForObject(
                "SELECT mil.issued_cost FROM material_issue_line mil "
                        + "JOIN material_issue mi2 ON mi2.id = mil.material_issue_id "
                        + "WHERE mi2.tenant_id = 0 AND mi2.doc_no = ? AND mil.line_no = 1",
                BigDecimal.class, miDocNo);
        BigDecimal issueTxnNeg = jdbc.queryForObject(
                "SELECT -SUM(total_cost) FROM inventory_transaction WHERE tenant_id = 0 "
                        + "AND txn_type = 'PRODUCTION_ISSUE' AND src_doc_no = ? AND src_line_no = 1",
                BigDecimal.class, miDocNo);
        assertThat(issuedCost).as("规则12 领料成本勾稽").isEqualByComparingTo(issueTxnNeg);

        // ===== 断言③（规则13 完工入库）：inbound_cost = Σ PRODUCTION_IN 流水金额 =====
        BigDecimal inboundCost = jdbc.queryForObject(
                "SELECT inbound_cost FROM production_report WHERE tenant_id = 0 AND doc_no = ?",
                BigDecimal.class, prDocNo);
        BigDecimal inTxnSum = jdbc.queryForObject(
                "SELECT SUM(total_cost) FROM inventory_transaction WHERE tenant_id = 0 "
                        + "AND txn_type = 'PRODUCTION_IN' AND src_doc_no = ?",
                BigDecimal.class, prDocNo);
        assertThat(inboundCost).as("规则13 完工入库成本勾稽").isEqualByComparingTo(inTxnSum);

        // ===== 断言④（规则14 料费守恒）：Σ完工入库料 = Σ净领料（无退料） =====
        BigDecimal sumInbound = jdbc.queryForObject(
                "SELECT COALESCE(SUM(inbound_cost),0) FROM production_report WHERE tenant_id = 0 "
                        + "AND status = 'COMPLETED' AND work_order_doc_no = ?",
                BigDecimal.class, woDocNo);
        BigDecimal sumIssued = jdbc.queryForObject(
                "SELECT COALESCE(SUM(mil.issued_cost),0) FROM material_issue_line mil "
                        + "JOIN material_issue mi3 ON mi3.id = mil.material_issue_id "
                        + "WHERE mi3.tenant_id = 0 AND mi3.status = 'COMPLETED' AND mi3.work_order_doc_no = ?",
                BigDecimal.class, woDocNo);
        assertThat(sumInbound).as("规则14 料费守恒 R1（无退料时 Σ完工入库料 = Σ净领料）")
                .isEqualByComparingTo(sumIssued);

        // ===== 断言⑤（规则15 完工量）：work_order.completed_qty = Σ报工 completed_qty =====
        BigDecimal woCompletedQty = jdbc.queryForObject(
                "SELECT completed_qty FROM work_order WHERE tenant_id = 0 AND doc_no = ?",
                BigDecimal.class, woDocNo);
        BigDecimal reportCompletedSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(completed_qty),0) FROM production_report WHERE tenant_id = 0 "
                        + "AND status = 'COMPLETED' AND work_order_doc_no = ?",
                BigDecimal.class, woDocNo);
        assertThat(woCompletedQty).as("规则15 工单完工量勾稽").isEqualByComparingTo(reportCompletedSum);

        // ===== 断言⑥（规则16 工费追加）：工费增量 = Σ COST_ADJUST 流水金额 =====
        BigDecimal expectedIncrement = jdbc.queryForObject(
                "SELECT (pl.completed_cost - pl.material_cost - pl.already_transferred) "
                        + "FROM production_cost_settlement_line pl "
                        + "JOIN production_cost_settlement ph ON ph.id = pl.settlement_id "
                        + "WHERE ph.tenant_id = 0 AND ph.doc_no = ? AND pl.line_no = 1",
                BigDecimal.class, pcDocNo);
        BigDecimal costAdjustSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_cost),0) FROM inventory_transaction WHERE tenant_id = 0 "
                        + "AND txn_type = 'COST_ADJUST' AND src_doc_no = ? AND src_line_no = 1",
                BigDecimal.class, pcDocNo);
        assertThat(expectedIncrement).as("规则16 成本结转工费增量 = Σ COST_ADJUST 流水")
                .isEqualByComparingTo(costAdjustSum);
        assertThat(costAdjustSum).as("本期确有工费增量追加").isGreaterThan(BigDecimal.ZERO);
    }

    // ======================================================================== 病态路径：退料-after-完工（规则14 纵深防御）

    /**
     * 退料-after-完工 病态路径验收（M5-T08 修复4 / 评审 P2-1）。
     *
     * <p>修复1（净额）只阻止「未来报工过度入库」，对「全部报工完成后再退料」无法回溯抵减已入库
     * （Σinbound 已固化），故 Σinbound &gt; Σissued_net 仍成立——此时<b>规则14 ERROR 是唯一防线</b>
     * （用户裁定明示保留为纵深防御）。本测试驱动该病态路径，验证规则14 三表 JOIN 真库聚合捕获 R1 破坏：
     * 建工单 → 领料过账 → 报工<b>全部完工入库</b>（料 100% 结转，Σinbound=Σissued）→ <b>退料过账</b>
     * （Σissued_net 降低，Σinbound &gt; Σissued_net）→ {@code check()} 应含 1 条
     * {@link ConsistencyCheckType#WORK_ORDER_MATERIAL_CONSERVATION} ERROR（key=该工单号）。
     *
     * <p>@Order(2) 在干净链路用例（@Order(1) 断言全库 0 ERROR）之后运行，避免本病态 ERROR 污染其全库断言
     * （共享容器、跨测试不清库）。
     */
    @Test
    @Order(2)
    void 退料after完工_规则14捕获料虚增ERROR_纵深防御() {
        long warehouseId = nextId();
        long componentId = nextId();
        long finishedId = nextId();
        long unitId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        LocalDate bizDate = LocalDate.now(ZoneOffset.UTC);
        seedProducts(unitId, componentId, finishedId, suffix);

        // 1. PURCHASE_IN 预置子件库存：100 件 @10.00（单位成本 10）
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(new InboundCommand(warehouseId, componentId,
                        InventoryTxnType.PURCHASE_IN, new BigDecimal("100"), new BigDecimal("10.00"),
                        null, "PURCHASE_RECEIPT", "PR-XCR-" + suffix, 1, "PURCH-IN-XCR:" + suffix + ":1"),
                        OPERATOR));

        // 2. 建工单（计划 10）→ release → start（EXECUTING）
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(finishedId, new BigDecimal("10"), unitId,
                        null, null, warehouseId, bizDate, bizDate, "退料后完工病态工单", OPERATOR));
        String woDocNo = jdbc.queryForObject(
                "SELECT doc_no FROM work_order WHERE product_id = ? AND tenant_id = 0 ORDER BY id DESC LIMIT 1",
                String.class, finishedId);
        txTemplate.executeWithoutResult(s -> workOrderService.release(woDocNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> workOrderService.start(woDocNo, OPERATOR));

        // 3. 领料 80 件子件（出库 800.00）→ 过账
        List<MaterialIssueLineInput> miLines = List.of(
                new MaterialIssueLineInput(componentId, new BigDecimal("80"), new BigDecimal("80"), unitId));
        MaterialIssue mi = txTemplate.execute(s ->
                materialIssueAppService.create(woDocNo, warehouseId, "领料", miLines, OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.approve(mi.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> materialIssueAppService.post(mi.getDocNo(), OPERATOR));

        // 4. 报工 8 件完工（料 800 全额结转入产成品，Σinbound=800=Σissued，无退料时守恒）
        List<ProductionReportLineInput> prLines = List.of(
                new ProductionReportLineInput(1, "工序1", null, new BigDecimal("10.000000"), null, unitId));
        ProductionReport pr = txTemplate.execute(s ->
                productionReportAppService.create(woDocNo, warehouseId, finishedId,
                        new BigDecimal("8"), null, unitId, "报工", prLines, OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.approve(pr.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.post(pr.getDocNo(), OPERATOR));

        // 5. 退料-after-完工：退 10 件子件（按原领料成本 100.00 入回），Σissued_net = 800 − 100 = 700，
        //    但 Σinbound 已固化 800 > 700 → R1 破坏（无法回溯抵减已入库）
        List<MaterialReturnLineInput> mrLines = List.of(
                new MaterialReturnLineInput(componentId, new BigDecimal("10"), unitId, 1));
        MaterialReturn mr = txTemplate.execute(s ->
                materialReturnAppService.create(mi.getDocNo(), warehouseId, "退料后完工", mrLines, OPERATOR));
        txTemplate.executeWithoutResult(s -> materialReturnAppService.approve(mr.getDocNo(), OPERATOR));
        MaterialReturn postedReturn = txTemplate.execute(s ->
                materialReturnAppService.post(mr.getDocNo(), OPERATOR));
        assertThat(postedReturn.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        // ===== 断言：规则14 捕获该工单料虚增 ERROR（纵深防御生效） =====
        ConsistencyReport report = consistencyCheckService.check();
        List<ConsistencyBreak> wo14Errors = report.breaks().stream()
                .filter(b -> b.checkType() == ConsistencyCheckType.WORK_ORDER_MATERIAL_CONSERVATION)
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> woDocNo.equals(b.key()))
                .toList();
        assertThat(wo14Errors).as("退料-after-完工 病态路径应被规则14 捕获 1 条料虚增 ERROR，实际: %s",
                report.breaks()).hasSize(1);
    }

    private static void seedProducts(long unitId, long componentId, long finishedId, String suffix) {
        jdbc.update("INSERT INTO unit (id, tenant_id, name, unit_precision, created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, 0, ?, 6, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))",
                unitId, "件-" + suffix, OPERATOR, OPERATOR);
        jdbc.update("INSERT INTO product (id, tenant_id, code, name, category_id, inventory_category, base_unit_id, "
                        + "status, created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, 0, ?, ?, NULL, 'RAW_MATERIAL', ?, 'ENABLED', ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))",
                componentId, "RAW-" + suffix, "原材料-" + suffix, unitId, OPERATOR, OPERATOR);
        jdbc.update("INSERT INTO product (id, tenant_id, code, name, category_id, inventory_category, base_unit_id, "
                        + "status, created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, 0, ?, ?, NULL, 'FINISHED_GOOD', ?, 'ENABLED', ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))",
                finishedId, "FG-" + suffix, "产成品-" + suffix, unitId, OPERATOR, OPERATOR);
    }
}
