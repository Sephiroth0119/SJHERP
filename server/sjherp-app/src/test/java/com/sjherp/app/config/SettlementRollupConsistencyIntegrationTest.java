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
import com.sjherp.app.consistency.ConsistencyCheckType;
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
import com.sjherp.domain.settlement.SettlementService;

/**
 * 核销 rollup 一致性校验真库集成测试（M4-T04c 验收，Testcontainers 真实 MySQL）。
 *
 * <p>承 {@link SettlementEngineIntegrationTest} 的发票驱动夹具与 {@link PurchaseToSalesFlowIntegrationTest}
 * 的 ConsistencyCheckService Bean 装配。两段验收：
 * <ol>
 *   <li><b>干净基线</b>：经 {@link SettlementService} 真实核销（应收部分→PARTIAL、应付全额→SETTLED、
 *       另留一笔未核销 OPEN）后跑 {@link ConsistencyCheckService#check()}，断言这些子账键
 *       <b>0 ERROR break</b>（核销正常路径下规则8/9/10 全绿，可作回归基线）；</li>
 *   <li><b>负向脏数据</b>：绕过 AppService 直接 UPDATE 子账 / INSERT 核销记录，分别制造
 *       「settled≠Σ记录」「settled>amount」「status 与余额不符」三类不一致，断言 recon 命中对应
 *       {@link ConsistencyCheckType#SETTLEMENT_ROLLUP}/{@code SETTLEMENT_OVER}/{@code SETTLEMENT_STATUS} ERROR
 *       （正常核销路径绝不会产生这些不一致，故须越权直插制造）。</li>
 * </ol>
 *
 * <p>应付/应收子账无外键约束，supplier/customer/warehouse/product id 自造隔离（同 SettlementEngine）。
 * 核销写动作经 {@link TransactionTemplate} 外层事务（等价 M4-T04 收付款单 AppService 的事务边界）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class SettlementRollupConsistencyIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-recon";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static PurchaseOrderService purchaseOrderService;
    private static PurchaseReceiptService purchaseReceiptService;
    private static PurchaseInvoiceService purchaseInvoiceService;
    private static SalesOrderService salesOrderService;
    private static SalesDeliveryService salesDeliveryService;
    private static SalesInvoiceService salesInvoiceService;
    private static SettlementService settlementService;
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
        purchaseOrderService = context.getBean(PurchaseOrderService.class);
        purchaseReceiptService = context.getBean(PurchaseReceiptService.class);
        purchaseInvoiceService = context.getBean(PurchaseInvoiceService.class);
        salesOrderService = context.getBean(SalesOrderService.class);
        salesDeliveryService = context.getBean(SalesDeliveryService.class);
        salesInvoiceService = context.getBean(SalesInvoiceService.class);
        settlementService = context.getBean(SettlementService.class);
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
            SalesInfraConfig.class, SettlementInfraConfig.class})
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

    // =================================================================================
    // 验收①：正常核销路径（PARTIAL / SETTLED / OPEN）后跑 recon → 这些子账键 0 ERROR break
    // =================================================================================

    @Test
    void 正常核销后_应收应付rollup键0ERROR_可作回归基线() {
        String suffix = Long.toString(System.nanoTime(), 36);
        LocalDate settleDate = LocalDate.of(2026, 6, 14);

        // 应收 1500.00 → 部分核销 600.00（PARTIAL）
        long arPartial = createReceivable(suffix + "p", "SINV-RP-" + suffix);
        txTemplate.executeWithoutResult(s ->
                settlementService.settleReceivable(arPartial, new BigDecimal("600.00"), settleDate, null, OPERATOR));
        // 应收 1500.00 → 不核销（OPEN）
        long arOpen = createReceivable(suffix + "o", "SINV-RO-" + suffix);
        // 应付 1250.00 → 全额核销（SETTLED）
        long apSettled = createPayable(suffix + "s", "PINV-PS-" + suffix);
        txTemplate.executeWithoutResult(s ->
                settlementService.settlePayable(apSettled, new BigDecimal("1250.00"), settleDate, null, OPERATOR));

        ConsistencyReport report = consistencyCheckService.check();
        // 收敛到本次三笔子账键，避免跨用例篡改行污染（check() 扫全库 tenant-0）。
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> isSettlementType(b.checkType()))
                .filter(b -> b.key() != null && (b.key().endsWith("#RECEIVABLE#" + arPartial)
                        || b.key().endsWith("#RECEIVABLE#" + arOpen)
                        || b.key().endsWith("#PAYABLE#" + apSettled)))
                .toList();
        assertThat(errors)
                .as("正常核销（PARTIAL/OPEN/SETTLED）下本三笔子账核销 rollup 应 0 ERROR，实际：%s", errors)
                .isEmpty();
    }

    // =================================================================================
    // 验收②-A：直插使 settled_amount 与 Σ核销记录不符 → 命中 SETTLEMENT_ROLLUP ERROR
    // =================================================================================

    @Test
    void 篡改子账settled与记录脱钩_命中SETTLEMENT_ROLLUP() {
        String suffix = Long.toString(System.nanoTime(), 36);
        LocalDate settleDate = LocalDate.of(2026, 6, 14);
        long arId = createReceivable(suffix, "SINV-DIRTY-RUP-" + suffix);
        // 先正常部分核销 600.00（settled=600，记录 Σ=600，PARTIAL）
        txTemplate.executeWithoutResult(s ->
                settlementService.settleReceivable(arId, new BigDecimal("600.00"), settleDate, null, OPERATOR));

        // 越权直插：子账 settled +1，核销记录不动 → rollup 与真源脱钩（仍 PARTIAL，0<601<1500、601<=1500，仅规则8 命中）
        jdbc.update("UPDATE accounts_receivable SET settled_amount = settled_amount + 1 "
                + "WHERE tenant_id = 0 AND id = ?", arId);

        List<ConsistencyBreak> hits = settlementBreaksOf("RECEIVABLE", arId);
        assertThat(hits).extracting(ConsistencyBreak::checkType)
                .contains(ConsistencyCheckType.SETTLEMENT_ROLLUP);
        ConsistencyBreak rollup = hits.stream()
                .filter(b -> b.checkType() == ConsistencyCheckType.SETTLEMENT_ROLLUP)
                .findFirst().orElseThrow();
        assertThat(rollup.severity()).isEqualTo(ConsistencySeverity.ERROR);
        // expected=Σ记录(600.00)、actual=子账settled(601.00)
        assertThat(new BigDecimal(rollup.expected())).isEqualByComparingTo("600.00");
        assertThat(new BigDecimal(rollup.actual())).isEqualByComparingTo("601.00");
    }

    // =================================================================================
    // 验收②-B：直插使 settled_amount > amount → 命中 SETTLEMENT_OVER ERROR
    // =================================================================================

    @Test
    void 越权直插超额_命中SETTLEMENT_OVER() {
        String suffix = Long.toString(System.nanoTime(), 36);
        long apId = createPayable(suffix, "PINV-DIRTY-OVER-" + suffix);
        // 绕过领域 settle 的 OverSettlement 校验：直插 settled=amount+0.01，并把核销记录补到同额、status=SETTLED。
        // 让规则8（rollup）通过、规则10（status 与余额）放过（余额=-0.01≠0 仍会命中，预期一并断言），隔离出规则9 必现。
        jdbc.update("UPDATE accounts_payable SET settled_amount = amount + 0.01, status = 'SETTLED' "
                + "WHERE tenant_id = 0 AND id = ?", apId);
        // 同步插一条核销记录使 Σ记录 == settled（规则8 不被掩盖，单验规则9/10）
        BigDecimal amount = jdbc.queryForObject(
                "SELECT amount FROM accounts_payable WHERE tenant_id = 0 AND id = ?", BigDecimal.class, apId);
        insertSettlementRecord("PAYABLE", apId, "PINV-DIRTY-OVER-" + suffix,
                amount.add(new BigDecimal("0.01")));

        List<ConsistencyBreak> hits = settlementBreaksOf("PAYABLE", apId);
        assertThat(hits).extracting(ConsistencyBreak::checkType)
                .doesNotContain(ConsistencyCheckType.SETTLEMENT_ROLLUP); // rollup 已被补平
        assertThat(hits).extracting(ConsistencyBreak::checkType)
                .contains(ConsistencyCheckType.SETTLEMENT_OVER);
        ConsistencyBreak over = hits.stream()
                .filter(b -> b.checkType() == ConsistencyCheckType.SETTLEMENT_OVER)
                .findFirst().orElseThrow();
        assertThat(over.severity()).isEqualTo(ConsistencySeverity.ERROR);
        assertThat(new BigDecimal(over.actual()))
                .as("actual=越权 settled 应 > amount").isGreaterThan(new BigDecimal(over.expected()));
    }

    // =================================================================================
    // 验收②-C：直插使 status 与余额不符 → 命中 SETTLEMENT_STATUS ERROR（且不命中 ROLLUP/OVER）
    // =================================================================================

    @Test
    void 篡改status与余额不符_仅命中SETTLEMENT_STATUS() {
        String suffix = Long.toString(System.nanoTime(), 36);
        long arId = createReceivable(suffix, "SINV-DIRTY-ST-" + suffix);
        // 全新应收：settled=0、记录 Σ=0、amount=1500、本应 OPEN。只改 status='SETTLED'（余额 1500>0 却标 SETTLED）。
        // 不动 settled/记录 → 规则8(rollup) 与规则9(over) 均不命中，仅隔离出规则10。
        jdbc.update("UPDATE accounts_receivable SET status = 'SETTLED' "
                + "WHERE tenant_id = 0 AND id = ?", arId);

        List<ConsistencyBreak> hits = settlementBreaksOf("RECEIVABLE", arId);
        assertThat(hits).extracting(ConsistencyBreak::checkType)
                .containsOnly(ConsistencyCheckType.SETTLEMENT_STATUS);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).severity()).isEqualTo(ConsistencySeverity.ERROR);
    }

    // ---------------------------------------------------------------
    // 工具：跑 recon、按子账类型+id 过滤本笔的核销类 ERROR break
    // ---------------------------------------------------------------

    private List<ConsistencyBreak> settlementBreaksOf(String type, long targetId) {
        String suffix = "#" + type + "#" + targetId;
        ConsistencyReport report = consistencyCheckService.check();
        return report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> isSettlementType(b.checkType()))
                .filter(b -> b.key() != null && b.key().endsWith(suffix))
                .toList();
    }

    private static boolean isSettlementType(ConsistencyCheckType type) {
        return type == ConsistencyCheckType.SETTLEMENT_ROLLUP
                || type == ConsistencyCheckType.SETTLEMENT_OVER
                || type == ConsistencyCheckType.SETTLEMENT_STATUS;
    }

    /** 直插核销记录（仅负向验证用：制造 Σ记录 与 settled 的对齐/脱钩样本）。 */
    private void insertSettlementRecord(String type, long targetId, String sourceDocNo, BigDecimal amount) {
        jdbc.update("INSERT INTO settlement_record (tenant_id, settlement_type, target_id, "
                        + "target_source_doc_no, amount, settlement_date, payment_doc_no, created_by, created_at) "
                        + "VALUES (0, ?, ?, ?, ?, ?, NULL, ?, NOW(6))",
                type, targetId, sourceDocNo, amount,
                java.sql.Date.valueOf(LocalDate.of(2026, 6, 14)), OPERATOR);
    }

    // ---------------------------------------------------------------
    // 夹具：经真实发票过账链路生成应收/应付（沿用 SettlementEngineIntegrationTest 驱动方式）
    // ---------------------------------------------------------------

    /** 采购 100@12.50 整链过账后返回生成的应付主键（应付 1250.00，来源 = 采购发票号）。 */
    private long createPayable(String suffix, String pinvNo) {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String poNo = "PO-RC-" + suffix;
        String prNo = "PR-RC-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "recon 夹具采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d, "收货",
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.post(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-RC", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY,
                OPERATOR));

        Long apId = jdbc.queryForObject("SELECT id FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, pinvNo);
        assertThat(apId).as("采购发票过账应生成应付").isNotNull();
        return apId;
    }

    /** 采购 100@12.50 备货 + 销售 60@25 整链过账后返回生成的应收主键（应收 1500.00，来源 = 销售发票号）。 */
    private long createReceivable(String suffix, String sinvNo) {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String poNo = "PO-RC-R-" + suffix;
        String prNo = "PR-RC-R-" + suffix;
        String pinvNo = "PINV-RC-R-" + suffix;
        String soNo = "SO-RC-R-" + suffix;
        String sdNo = "SD-RC-R-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        // 备货：采购 100@12.50 入库过账（库存 100/1250.00）
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "备货采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d, "收货",
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.post(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-RC-R", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY,
                OPERATOR));

        // 销售：60@20 下单 → 出库过账 → 60@25 发票过账（应收 1500.00）
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, d, "recon 夹具销售",
                List.of(new SalesOrderLineInput(productId, new BigDecimal("60"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(soNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(sdNo, soNo, warehouseId, "发货",
                List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("60"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(sdNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.post(sdNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(sinvNo, sdNo, customerId, d,
                d.plusMonths(1), "开票",
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("60"),
                        new BigDecimal("25"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(sinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.post(sinvNo, OPERATOR));

        Long arId = jdbc.queryForObject("SELECT id FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, sinvNo);
        assertThat(arId).as("销售发票过账应生成应收").isNotNull();
        return arId;
    }
}
