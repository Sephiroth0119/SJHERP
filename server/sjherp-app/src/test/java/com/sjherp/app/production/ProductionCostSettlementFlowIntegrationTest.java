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
import com.sjherp.app.config.GlInfraConfig;
import com.sjherp.app.config.InventoryInfraConfig;
import com.sjherp.app.config.ProductionCostInfraConfig;
import com.sjherp.app.config.ProductionInfraConfig;
import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.app.inventory.JdbcStockChecker;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLineInput;
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLineInput;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLineInput;

/**
 * 生产成本归集与结转端到端验收（M5-T06 设计真源 §7，全项目最难财务点）。
 *
 * <p>Testcontainers 真实 MySQL + Flyway 全量迁移，生产同套装配
 * （AuditConfig + InventoryInfraConfig + CatalogInfraConfig + GlInfraConfig + ProductionInfraConfig），
 * 驱动：PURCHASE_IN 预置子件 → 工单 → 领料(T05 出库) → 报工(T05 料入产成品) → 月末成本结转(T06)，
 * 断言：
 * <ol>
 *   <li>产成品金额 = 料(T05) + 完工工费(T06)、数量不变（COST_ADJUST NEUTRAL）；</li>
 *   <li>GL 出现 5001/5101/2211/1403/1405 凭证行且 Σ借=Σ贷；</li>
 *   <li>5001 借方余额 = 期末 WIP（在产工费）；</li>
 *   <li>ConsistencyCheckService 0 ERROR；</li>
 *   <li>幂等：重 post 不重复入账；</li>
 *   <li>关账衔接 PeriodCloseService.close 成功，5001 留 WIP（COST 类不参与损益结转）。</li>
 * </ol>
 */
@Tag("integration-db")
class ProductionCostSettlementFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-cost";
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static TransactionalWorkOrderService workOrderService;
    private static MaterialIssueAppService materialIssueAppService;
    private static ProductionReportAppService productionReportAppService;
    private static ProductionCostSettlementAppService costSettlementAppService;
    private static ConsistencyCheckService consistencyCheckService;
    private static PeriodCloseService periodCloseService;
    private static AccountingPeriodService periodService;

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
        productionReportAppService = context.getBean(ProductionReportAppService.class);
        costSettlementAppService = context.getBean(ProductionCostSettlementAppService.class);
        consistencyCheckService = context.getBean(ConsistencyCheckService.class);
        periodCloseService = context.getBean(PeriodCloseService.class);
        periodService = context.getBean(AccountingPeriodService.class);
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

        @Bean
        PeriodCloseService periodCloseService(VoucherService voucherService,
                                              AccountService accountService,
                                              AccountingPeriodService accountingPeriodService,
                                              ConsistencyCheckService consistencyCheckService,
                                              DocumentNumberGenerator documentNumberGenerator) {
            return new PeriodCloseService(voucherService, accountService, accountingPeriodService,
                    consistencyCheckService, documentNumberGenerator);
        }

        /** CatalogInfraConfig.productService 依赖（照 ProductionReportFlowIntegrationTest）。 */
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
    void 月末成本结转_产成品料工费_GL平衡_5001留WIP_一致性0ERROR_幂等_关账衔接() {
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        String period = ym.format(DateTimeFormatter.ofPattern("yyyyMM"));
        LocalDate bizDate = ym.atDay(Math.min(15, ym.lengthOfMonth()));

        long warehouseId = nextId();
        long componentId = nextId();
        long finishedId = nextId();
        long unitId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);

        // 开账期 + 写成本参数（人工费率 20、制造费用率 5）
        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        jdbc.update("INSERT INTO production_cost_param "
                + "(tenant_id, period, default_labor_rate, overhead_rate, created_by, created_at, "
                + "updated_by, updated_at) VALUES (0, ?, 20, 5, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))",
                period, OPERATOR, OPERATOR);
        seedProducts(unitId, componentId, finishedId, suffix);

        // 1. PURCHASE_IN 预置子件库存：100 件 @10.00，金额 1000.00
        txTemplate.executeWithoutResult(s ->
                inventoryService.inbound(new InboundCommand(warehouseId, componentId,
                        InventoryTxnType.PURCHASE_IN, new BigDecimal("100"), new BigDecimal("10.00"),
                        null, "PURCHASE_RECEIPT", "PR-CS-" + suffix, 1, "PURCH-IN-CS:" + suffix + ":1"),
                        OPERATOR));

        // 2. 建工单（计划 10）→ release → start（→ EXECUTING）
        txTemplate.executeWithoutResult(s ->
                workOrderService.createManual(finishedId, new BigDecimal("10"), unitId,
                        null, null, warehouseId, bizDate, bizDate, "成本结转测试工单", OPERATOR));
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

        // 4. 报工 8 件完工（T05：料 800 入产成品），工时 10 小时
        List<ProductionReportLineInput> prLines = List.of(
                new ProductionReportLineInput(1, "工序1", null, new BigDecimal("10.000000"), null, unitId));
        ProductionReport pr = txTemplate.execute(s ->
                productionReportAppService.create(woDocNo, warehouseId, finishedId,
                        new BigDecimal("8"), null, unitId, "报工", prLines, OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.approve(pr.getDocNo(), OPERATOR));
        txTemplate.executeWithoutResult(s -> productionReportAppService.post(pr.getDocNo(), OPERATOR));

        // T05 后：产成品 8 件，料成本 800.00（料随完工入库守恒）
        assertBalanceQty(warehouseId, finishedId, "8");
        assertBalanceAmount(warehouseId, finishedId, "800.00");

        // 5. 月末成本结转：完工 8、在产 2 @50%。工费 = 工 10×20=200 + 费 10×5=50 = 250
        //    在产约当 = 2×0.5 = 1，总约当 = 8+1 = 9，单位 = 250/9 = 27.777778
        //    在产工费 = 1×27.777778 = 27.78；完工工费 = 250 − 27.78 = 222.22（尾差并入完工）
        ProductionCostSettlement cs = txTemplate.execute(s ->
                costSettlementAppService.create(period, "月末结转",
                        List.of(new ProductionCostSettlementLineInput(woDocNo,
                                new BigDecimal("2"), new BigDecimal("50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> costSettlementAppService.approve(cs.getDocNo(), OPERATOR));
        ProductionCostSettlement posted = txTemplate.execute(s ->
                costSettlementAppService.post(cs.getDocNo(), OPERATOR));
        assertThat(posted.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        // 断言①：产成品数量不变（8），金额 = 料 800 + 完工工费增量 222.22 = 1022.22
        assertBalanceQty(warehouseId, finishedId, "8");
        assertBalanceAmount(warehouseId, finishedId, "1022.22");

        // COST_ADJUST 流水存在
        int costAdjustCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? "
                        + "AND product_id = ? AND txn_type = 'COST_ADJUST'",
                Integer.class, warehouseId, finishedId);
        assertThat(costAdjustCount).isEqualTo(1);

        // 断言②：GL 凭证 Σ借=Σ贷（本结转单生成的凭证）
        String voucherDocNo = posted.getLines().get(0).getVoucherDocNo();
        assertThat(voucherDocNo).isNotNull();
        BigDecimal vDebit = jdbc.queryForObject(
                "SELECT COALESCE(SUM(vl.debit),0) FROM voucher_line vl "
                        + "JOIN voucher v ON v.id = vl.voucher_id AND v.tenant_id = vl.tenant_id "
                        + "WHERE v.tenant_id = 0 AND v.doc_no = ?", BigDecimal.class, voucherDocNo);
        BigDecimal vCredit = jdbc.queryForObject(
                "SELECT COALESCE(SUM(vl.credit),0) FROM voucher_line vl "
                        + "JOIN voucher v ON v.id = vl.voucher_id AND v.tenant_id = vl.tenant_id "
                        + "WHERE v.tenant_id = 0 AND v.doc_no = ?", BigDecimal.class, voucherDocNo);
        assertThat(vDebit).isEqualByComparingTo(vCredit);
        // 凭证含 5001/5101/2211/1403/1405 各科目行
        assertAccountAppears(voucherDocNo, "5001");
        assertAccountAppears(voucherDocNo, "5101");
        assertAccountAppears(voucherDocNo, "2211");
        assertAccountAppears(voucherDocNo, "1403");
        assertAccountAppears(voucherDocNo, "1405");

        // 断言③：5001 借方净额 = 期末 WIP = 在产工费 27.78（料归集 800+工费 250 入借，完工结转 1022.22 出贷）
        // 5001 借 = 料 800 + 人工 200 + 制造费用转入 50 = 1050；5001 贷 = 完工结转 1022.22
        // 净 = 1050 − 1022.22 = 27.78 = 在产工费（料随完工 100% 结转，在产仅含工费）
        BigDecimal acc5001 = accountNetDebit("5001", period);
        assertThat(acc5001).as("5001 借方净额 = 期末 WIP 在产工费").isEqualByComparingTo("27.78");

        // 断言④：一致性 0 ERROR（库存恒等式、核销 rollup 等；规则11 完工工费已结转为 WARN 不计）
        ConsistencyReport report = consistencyCheckService.check();
        String finishedKey = "warehouse=" + warehouseId + ",product=" + finishedId;
        String componentKey = "warehouse=" + warehouseId + ",product=" + componentId;
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> b.key() != null
                        && (b.key().contains(finishedKey) || b.key().contains(componentKey)))
                .toList();
        assertThat(errors).as("成本结转后相关库存维度应 0 ERROR，实际: %s", errors).isEmpty();

        // 断言⑤：幂等——再 post 已 COMPLETED 单据被状态机拒（不重复入账）
        BigDecimal amountBeforeRepost = jdbc.queryForObject(
                "SELECT cost_amount FROM inventory_balance WHERE tenant_id=0 AND warehouse_id=? AND product_id=?",
                BigDecimal.class, warehouseId, finishedId);
        try {
            txTemplate.executeWithoutResult(s -> costSettlementAppService.post(cs.getDocNo(), OPERATOR));
        } catch (RuntimeException expected) {
            // COMPLETED → EXECUTING 非法流转被拒
        }
        BigDecimal amountAfterRepost = jdbc.queryForObject(
                "SELECT cost_amount FROM inventory_balance WHERE tenant_id=0 AND warehouse_id=? AND product_id=?",
                BigDecimal.class, warehouseId, finishedId);
        assertThat(amountAfterRepost).as("重 post 不改变产成品金额（幂等）")
                .isEqualByComparingTo(amountBeforeRepost);

        // 断言⑥：关账衔接——月末关账成功，5001 留 WIP 余额（COST 类不参与损益结转）
        PeriodCloseResult closeResult = txTemplate.execute(s ->
                periodCloseService.close(period, OPERATOR));
        assertThat(closeResult).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM accounting_period WHERE tenant_id=0 AND period=?", String.class, period))
                .isEqualTo("CLOSED");
        // 关账后 5001 仍留 WIP 27.78（COST 类未被损益结转触碰）
        assertThat(accountNetDebit("5001", period)).isEqualByComparingTo("27.78");
    }

    // ======================================================================== SQL 旁证

    private void assertBalanceQty(long warehouseId, long productId, String expected) {
        BigDecimal qty = jdbc.queryForObject(
                "SELECT quantity FROM inventory_balance WHERE tenant_id=0 AND warehouse_id=? AND product_id=?",
                BigDecimal.class, warehouseId, productId);
        assertThat(qty).isNotNull().isEqualByComparingTo(new BigDecimal(expected));
    }

    private void assertBalanceAmount(long warehouseId, long productId, String expected) {
        BigDecimal amt = jdbc.queryForObject(
                "SELECT cost_amount FROM inventory_balance WHERE tenant_id=0 AND warehouse_id=? AND product_id=?",
                BigDecimal.class, warehouseId, productId);
        assertThat(amt).isNotNull().isEqualByComparingTo(new BigDecimal(expected));
    }

    private void assertAccountAppears(String voucherDocNo, String accountCode) {
        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher_line vl JOIN voucher v ON v.id = vl.voucher_id "
                        + "AND v.tenant_id = vl.tenant_id "
                        + "WHERE v.tenant_id = 0 AND v.doc_no = ? AND vl.account_code = ?",
                Integer.class, voucherDocNo, accountCode);
        assertThat(count).as("凭证 %s 应含科目 %s 的行", voucherDocNo, accountCode).isGreaterThan(0);
    }

    /** 某科目某账期已过账凭证借−贷净额（借方为正）。 */
    private BigDecimal accountNetDebit(String accountCode, String period) {
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(vl.debit - vl.credit), 0) FROM voucher_line vl "
                        + "JOIN voucher v ON v.id = vl.voucher_id AND v.tenant_id = vl.tenant_id "
                        + "WHERE v.tenant_id = 0 AND v.period = ? AND v.status IN ('APPROVED','REVERSED') "
                        + "AND vl.account_code = ?",
                BigDecimal.class, period, accountCode);
        return net == null ? BigDecimal.ZERO : net;
    }

    private void seedProducts(long unitId, long componentId, long finishedId, String suffix) {
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
