package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
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

import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyCheckType;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.consistency.ConsistencyCheckRunner;
import com.sjherp.app.consistency.ConsistencyRule;
import com.sjherp.app.consistency.ConsistencyRuleRegistry;
import com.sjherp.app.consistency.ConsistencyRunPersistenceService;
import com.sjherp.app.consistency.ConsistencyConfig;
import com.sjherp.app.notification.InAppNotificationChannel;
import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;
import com.sjherp.app.finance.FinancialStatementDao;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheetLine;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatementLine;
import com.sjherp.app.finance.FinancialStatementService;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.PeriodStatus;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceLineInput;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLineInput;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceLineInput;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderLineInput;
import com.sjherp.domain.sales.SalesOrderService;
import com.sjherp.infra.persistence.JdbcSequenceProvider;

/**
 * 财务报表（资产负债表 + 利润表）整月验收集成测试（M4-T06 验收主测，设计真源 §5 集成七步，
 * Testcontainers 真实 MySQL）。
 *
 * <p>用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link PurchaseInfraConfig} + {@link SalesInfraConfig} + {@link GlInfraConfig}）跑通完整链路并验收
 * {@link FinancialStatementService} 的两张报表，重点对账三组不变式：
 * <ol>
 *   <li><b>关账前</b>利润表：营业收入=销售额、营业成本=COGS、净利润=毛利（此时尚无结转凭证）；</li>
 *   <li><b>关账前</b>资产负债表：balanced=true（未结转损益折入未分配利润）、应收=1122 余额、
 *       存货=1405 余额、应付=2202 余额对账；</li>
 *   <li>close(P) 关账 → 生成结转凭证；</li>
 *   <li><b>关账后</b>利润表：数值<b>完全不变</b>（DAO 排除 PERIOD_CLOSING 结转凭证）、净利润 ==
 *       close 返回的 netProfit（交叉校验）；</li>
 *   <li><b>关账后</b>资产负债表：balanced=true、未分配利润含 4103=毛利、损益类归零不影响。</li>
 * </ol>
 *
 * <p><b>账期取当前 UTC 月</b>：{@link AutoVoucherService#generateForSalesDelivery} 的 COGS 凭证日期
 * 取 {@code LocalDate.now(UTC)}（出库单无业务日），故全部业务凭证必须落<b>当前 UTC 月</b>才同期。
 * 本测试用 {@code YearMonth.now(UTC)} 派生账期 P（与 {@link PeriodCloseFlowIntegrationTest} 同款裁定），
 * 所有业务日期取该月内固定一日。利润表本年累计区间 [yyyy01, P] 同年覆盖本期，本测试只造一个月业务，
 * 故本期=本年累计。
 *
 * <p>驱动方式（与 {@link PeriodCloseFlowIntegrationTest} 一致）：经各<b>领域服务</b>直驱 + 同事务内
 * 显式 {@link AutoVoucherService}（等价 AppService.post 的「过账 + 自动凭证」原子边界），自造
 * supplier/customer/warehouse/product id 隔离数据（各表无外键约束）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class FinancialStatementFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static PurchaseOrderService purchaseOrderService;
    private static PurchaseReceiptService purchaseReceiptService;
    private static PurchaseInvoiceService purchaseInvoiceService;
    private static SalesOrderService salesOrderService;
    private static SalesDeliveryService salesDeliveryService;
    private static SalesInvoiceService salesInvoiceService;
    private static AutoVoucherService autoVoucherService;
    private static AccountingPeriodService periodService;
    private static PeriodCloseService periodCloseService;
    private static FinancialStatementService financialStatementService;
    private static ConsistencyCheckService consistencyCheckService;
    private static ConsistencyCheckRunner consistencyCheckRunner;
    private static InAppNotificationChannel notificationChannel;

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
        purchaseOrderService = context.getBean(PurchaseOrderService.class);
        purchaseReceiptService = context.getBean(PurchaseReceiptService.class);
        purchaseInvoiceService = context.getBean(PurchaseInvoiceService.class);
        salesOrderService = context.getBean(SalesOrderService.class);
        salesDeliveryService = context.getBean(SalesDeliveryService.class);
        salesInvoiceService = context.getBean(SalesInvoiceService.class);
        autoVoucherService = context.getBean(AutoVoucherService.class);
        periodService = context.getBean(AccountingPeriodService.class);
        periodCloseService = context.getBean(PeriodCloseService.class);
        financialStatementService = context.getBean(FinancialStatementService.class);
        consistencyCheckService = context.getBean(ConsistencyCheckService.class);
        consistencyCheckRunner = context.getBean(ConsistencyCheckRunner.class);
        notificationChannel = context.getBean(InAppNotificationChannel.class);
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
    @Import({AuditConfig.class, ConsistencyConfig.class, InventoryInfraConfig.class, PurchaseInfraConfig.class,
            SalesInfraConfig.class, GlInfraConfig.class, ProductRepositoryTestConfig.class})
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

        // 凭证号生成器（GlInfraConfig 的 AutoVoucherService 依赖；隔离上下文显式 new）
        @Bean
        SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
            return new JdbcSequenceProvider(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
            return new DefaultDocumentNumberGenerator(sequenceProvider);
        }

        // 一致性校验单元（PeriodCloseService 的结转前闸门依赖；显式 new，allow-negative=false）
        @Bean
        ConsistencyCheckDao consistencyCheckDao(JdbcTemplate jdbcTemplate) {
            return new ConsistencyCheckDao(jdbcTemplate);
        }

        @Bean
        ConsistencyCheckService consistencyCheckService(ConsistencyCheckDao dao) {
            return new ConsistencyCheckService(dao, false);
        }

        @Bean
        InAppNotificationChannel inAppNotificationChannel() {
            return mock(InAppNotificationChannel.class);
        }

        @Bean
        ConsistencyRunPersistenceService consistencyRunPersistenceService(
                ConsistencyCheckRunRepository repository, InAppNotificationChannel channel) {
            return new ConsistencyRunPersistenceService(repository, channel);
        }

        @Bean
        ConsistencyCheckRunner consistencyCheckRunner(ConsistencyCheckService service,
                DocumentNumberGenerator numberGenerator, ConsistencyRunPersistenceService persistence) {
            ConsistencyRule core = new com.sjherp.app.consistency.CoreSqlAssertionRule(service);
            return new ConsistencyCheckRunner(new ConsistencyRuleRegistry(List.of(core)), numberGenerator, persistence);
        }

        // 月末结转关账编排器（注入顺序同生产构造器）
        @Bean
        PeriodCloseService periodCloseService(VoucherService voucherService,
                                              AccountService accountService,
                                              AccountingPeriodService accountingPeriodService,
                                              ConsistencyCheckService consistencyCheckService,
                                              DocumentNumberGenerator documentNumberGenerator) {
            return new PeriodCloseService(voucherService, accountService, accountingPeriodService,
                    consistencyCheckService, documentNumberGenerator);
        }

        // 财务报表只读 DAO + 应用服务（生产由组件扫描装配；此处显式 new）
        @Bean
        FinancialStatementDao financialStatementDao(JdbcTemplate jdbcTemplate) {
            return new FinancialStatementDao(jdbcTemplate);
        }

        @Bean
        FinancialStatementService financialStatementService(FinancialStatementDao dao,
                                                            AccountService accountService) {
            return new FinancialStatementService(dao, accountService);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // ===============================================================
    // 验收主测：整月业务 → 关账前报表对账 → close → 关账后报表数值不变 + 仍平衡
    // ===============================================================

    @Test
    void 整月业务后报表对账_关账前后利润表不变_资产负债表恒平衡_净利润等于毛利() {
        // 步①：账期取当前 UTC 月（COGS 凭证日 = now(UTC)，须与其余业务凭证同期）。
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        String period = ym.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        LocalDate bizDate = ym.atDay(Math.min(15, ym.lengthOfMonth()));

        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-FS-" + suffix;
        String prNo = "PR-FS-" + suffix;
        String pinvNo = "PINV-FS-" + suffix;
        String soNo = "SO-FS-" + suffix;
        String sdNo = "SD-FS-" + suffix;
        String sinvNo = "SINV-FS-" + suffix;

        // 开账期 P。
        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        assertThat(periodService.isOpen(period)).isTrue();

        // 步②：模拟整月业务（领域服务驱动 + 同事务内 T02 自动凭证）。
        // 采购：下单 100@12.50 → 审核 → 收 100 过账（库存 100/1250.00，借1405/贷220201 1250.00）
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, bizDate,
                "整月采购", List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId,
                bizDate, "收货", List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            PurchaseReceipt receipt = purchaseReceiptService.post(prNo, OPERATOR);
            autoVoucherService.generateForPurchaseReceipt(receipt, OPERATOR);
        });

        // 采购发票：开 100 / 金额 1250.00 → 审核 → 过账（借220201/贷220202 1250.00，应付 1250.00）
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, bizDate, "INV-FS", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            PurchaseInvoice invoice = purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY,
                    OPERATOR);
            autoVoucherService.generateForPurchaseInvoice(invoice, OPERATOR);
        });

        // 销售：下单 60@20 → 审核 → 出 60 过账（COGS=60×12.50=750.00，借6401/贷1405 750.00，库存→40/500.00）
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, bizDate,
                "整月销售", List.of(new SalesOrderLineInput(productId, new BigDecimal("60"),
                        new BigDecimal("20"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(soNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(sdNo, soNo, warehouseId,
                "发货", List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("60"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(sdNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            SalesDelivery delivery = salesDeliveryService.post(sdNo, OPERATOR);
            autoVoucherService.generateForSalesDelivery(delivery, OPERATOR);
        });

        // 销售发票：对出库行 60@25 开票 → 审核 → 过账（借1122/贷6001 1500.00，应收 1500.00）
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(sinvNo, sdNo, customerId,
                bizDate, bizDate.plusMonths(1), "开票",
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("60"),
                        new BigDecimal("25"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(sinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            SalesInvoice invoice = salesInvoiceService.post(sinvNo, OPERATOR);
            autoVoucherService.generateForSalesInvoice(invoice, OPERATOR);
        });

        // 预期数值：收入 6001 1500.00；成本 6401 750.00；毛利 750.00。
        // 资产侧：1122 应收 1500.00、1405 存货余额 500.00；负债侧：2202(220201+220202) 应付 1250.00。
        BigDecimal expectedRevenue = new BigDecimal("1500.00");
        BigDecimal expectedCogs = new BigDecimal("750.00");
        BigDecimal expectedProfit = new BigDecimal("750.00");
        BigDecimal expectedReceivable = new BigDecimal("1500.00");
        BigDecimal expectedInventory = new BigDecimal("500.00");
        BigDecimal expectedPayable = new BigDecimal("1250.00");

        // ============ 步③：关账前利润表 ============
        IncomeStatement isBefore = financialStatementService.incomeStatement(period);
        assertThat(isBefore.period()).isEqualTo(period);
        // 营业收入本期 = 销售额 1500.00（本年累计同值，仅一月业务）
        assertThat(new BigDecimal(currentOf(isBefore, "一、营业收入")))
                .as("关账前营业收入=销售额").isEqualByComparingTo(expectedRevenue);
        assertThat(new BigDecimal(ytdOf(isBefore, "一、营业收入")))
                .as("营业收入本年累计=本期（仅一月业务）").isEqualByComparingTo(expectedRevenue);
        // 营业成本本期 = COGS 750.00
        assertThat(new BigDecimal(currentOf(isBefore, "减：营业成本")))
                .as("关账前营业成本=COGS").isEqualByComparingTo(expectedCogs);
        // 净利润本期 = 毛利 750.00（无税费/其它费用）
        assertThat(new BigDecimal(isBefore.netProfitCurrent()))
                .as("关账前净利润=毛利").isEqualByComparingTo(expectedProfit);
        assertThat(new BigDecimal(isBefore.netProfitYtd()))
                .as("关账前净利润本年累计=毛利").isEqualByComparingTo(expectedProfit);
        // 净利润两口径冗余暴露与"净利润"行同值
        assertThat(new BigDecimal(currentOf(isBefore, "四、净利润")))
                .isEqualByComparingTo(new BigDecimal(isBefore.netProfitCurrent()));

        // ============ 步④：关账前资产负债表 ============
        BalanceSheet bsBefore = financialStatementService.balanceSheet(period);
        assertThat(bsBefore.period()).isEqualTo(period);
        assertThat(bsBefore.balanced()).as("关账前资产负债表平衡（未结转损益折入未分配利润）").isTrue();
        // 资产总计 == 负债合计 + 权益合计
        assertThat(new BigDecimal(bsBefore.totalAssets()))
                .as("关账前 资产总计 == 负债合计 + 权益合计")
                .isEqualByComparingTo(new BigDecimal(bsBefore.totalLiabilities())
                        .add(new BigDecimal(bsBefore.totalEquity())));
        // 应收 = 1122 余额 1500.00
        assertThat(new BigDecimal(assetLine(bsBefore, "应收账款")))
                .as("应收账款=1122 余额").isEqualByComparingTo(expectedReceivable);
        // 存货 = 1405 余额 500.00
        assertThat(new BigDecimal(assetLine(bsBefore, "存货")))
                .as("存货=1405 余额").isEqualByComparingTo(expectedInventory);
        // 应付 = 220201+220202 余额 1250.00
        assertThat(new BigDecimal(liabilityLine(bsBefore, "应付账款")))
                .as("应付账款=2202 余额").isEqualByComparingTo(expectedPayable);
        // 关账前未分配利润含本期未结转损益（=毛利 750.00；本场景无 4103/4104 期初）
        assertThat(new BigDecimal(equityLine(bsBefore, "未分配利润")))
                .as("关账前未分配利润含本期未结转损益（=毛利）").isEqualByComparingTo(expectedProfit);

        // ============ 步⑤：close(P) 关账 → 生成结转凭证 ============
        PeriodCloseResult result = txTemplate.execute(s ->
                periodCloseService.close(period, OPERATOR));
        assertThat(result).isNotNull();
        assertThat(periodService.get(period).getStatus()).isEqualTo(PeriodStatus.CLOSED);

        // ============ 步⑥：关账后利润表 —— 数值完全不变（结转被排除）+ 净利润==close netProfit ============
        IncomeStatement isAfter = financialStatementService.incomeStatement(period);
        assertThat(new BigDecimal(currentOf(isAfter, "一、营业收入")))
                .as("关账后营业收入不变").isEqualByComparingTo(expectedRevenue);
        assertThat(new BigDecimal(currentOf(isAfter, "减：营业成本")))
                .as("关账后营业成本不变").isEqualByComparingTo(expectedCogs);
        assertThat(new BigDecimal(isAfter.netProfitCurrent()))
                .as("关账后净利润不变（DAO 排除 PERIOD_CLOSING 结转凭证）")
                .isEqualByComparingTo(expectedProfit);
        // 交叉校验：利润表净利润 == close 返回的 netProfit
        assertThat(new BigDecimal(isAfter.netProfitCurrent()))
                .as("利润表净利润 == close 返回 netProfit（交叉校验）")
                .isEqualByComparingTo(new BigDecimal(result.netProfit()));
        // 逐行与关账前完全一致（§1.2 最易错点：结转前后利润表恒等）
        assertThat(isAfter.lines()).hasSameSizeAs(isBefore.lines());
        for (int i = 0; i < isAfter.lines().size(); i++) {
            IncomeStatementLine before = isBefore.lines().get(i);
            IncomeStatementLine after = isAfter.lines().get(i);
            assertThat(after.name()).isEqualTo(before.name());
            assertThat(new BigDecimal(after.currentPeriod()))
                    .as("利润表行[%s]本期关账前后不变", after.name())
                    .isEqualByComparingTo(new BigDecimal(before.currentPeriod()));
            assertThat(new BigDecimal(after.yearToDate()))
                    .as("利润表行[%s]本年累计关账前后不变", after.name())
                    .isEqualByComparingTo(new BigDecimal(before.yearToDate()));
        }

        // ============ 步⑦：关账后资产负债表 —— 仍平衡 + 未分配利润含 4103=毛利 + 损益归零不影响 ============
        BalanceSheet bsAfter = financialStatementService.balanceSheet(period);
        assertThat(bsAfter.balanced()).as("关账后资产负债表仍平衡").isTrue();
        assertThat(new BigDecimal(bsAfter.totalAssets()))
                .as("关账后 资产总计 == 负债合计 + 权益合计")
                .isEqualByComparingTo(new BigDecimal(bsAfter.totalLiabilities())
                        .add(new BigDecimal(bsAfter.totalEquity())));
        // 关账后损益类已归零 → 未分配利润完全由 4103 承接（=毛利 750.00），数值不变。
        assertThat(new BigDecimal(equityLine(bsAfter, "未分配利润")))
                .as("关账后未分配利润含 4103=毛利（结转后由本年利润承接，数值不变）")
                .isEqualByComparingTo(expectedProfit);
        // 资产侧时点科目（应收/存货）关账前后不变（结转只动损益类，不碰资产负债类）。
        assertThat(new BigDecimal(assetLine(bsAfter, "应收账款")))
                .as("关账后应收账款不变").isEqualByComparingTo(expectedReceivable);
        assertThat(new BigDecimal(assetLine(bsAfter, "存货")))
                .as("关账后存货不变").isEqualByComparingTo(expectedInventory);
        assertThat(new BigDecimal(liabilityLine(bsAfter, "应付账款")))
                .as("关账后应付账款不变").isEqualByComparingTo(expectedPayable);
        // 资产总计关账前后不变（损益结转是权益内部腾挪：未结转损益→本年利润，权益合计不变）。
        assertThat(new BigDecimal(bsAfter.totalAssets()))
                .as("关账后资产总计与关账前一致")
                .isEqualByComparingTo(new BigDecimal(bsBefore.totalAssets()));
        assertThat(new BigDecimal(bsAfter.totalEquity()))
                .as("关账后权益合计与关账前一致（损益结转为权益内部腾挪）")
                .isEqualByComparingTo(new BigDecimal(bsBefore.totalEquity()));

        // M6-T06 验收：先确认干净基线，再只污染 1122 控制科目侧；凭证仍自平衡，下一周期必须揪出 GL_DETAIL。
        var baseline = consistencyCheckRunner.runScheduled();
        assertThat(baseline.clean()).isTrue();
        assertThat(baseline.findings()).noneMatch(f -> f.checkType().equals(ConsistencyCheckType.GL_DETAIL.code()));
        assertThat(baseline.findings()).noneMatch(f -> f.checkType().equals(ConsistencyCheckType.VOUCHER_BALANCE.code()));
        Long voucherId = jdbc.queryForObject("SELECT vl.voucher_id FROM voucher_line vl JOIN voucher v ON v.id = vl.voucher_id "
                + "WHERE vl.tenant_id = 0 AND vl.account_code = '1122' AND v.status IN ('APPROVED','REVERSED') LIMIT 1", Long.class);
        Long controlLineId = jdbc.queryForObject("SELECT vl.id FROM voucher_line vl WHERE vl.voucher_id = ? AND vl.account_code = '1122' LIMIT 1",
                Long.class, voucherId);
        Long offsetLineId = jdbc.queryForObject("SELECT vl.id FROM voucher_line vl WHERE vl.voucher_id = ? AND vl.account_code <> '1122' "
                + "AND vl.credit > 0 LIMIT 1", Long.class, voucherId);
        clearInvocations(notificationChannel);
        try {
            jdbc.update("UPDATE voucher_line SET debit = debit + 1.00 WHERE id = ?", controlLineId);
            jdbc.update("UPDATE voucher_line SET credit = credit + 1.00 WHERE id = ?", offsetLineId);
            jdbc.update("UPDATE voucher SET total_amount = total_amount + 1.00 WHERE id = ?", voucherId);
            var run = consistencyCheckRunner.runScheduled();
            assertThat(run.clean()).isFalse();
            assertThat(run.findings()).anyMatch(f -> f.checkType().equals(ConsistencyCheckType.GL_DETAIL.code()));
            assertThat(run.findings()).noneMatch(f -> f.checkType().equals(ConsistencyCheckType.VOUCHER_BALANCE.code()));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consistency_check_run WHERE run_no = ?", Long.class, run.runNo())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consistency_check_break WHERE run_id = "
                    + "(SELECT id FROM consistency_check_run WHERE run_no = ?)", Long.class, run.runNo())).isGreaterThan(0L);
            verify(notificationChannel).send(argThat(n -> n.runNo().equals(run.runNo())));
        } finally {
            jdbc.update("UPDATE voucher_line SET debit = debit - 1.00 WHERE id = ?", controlLineId);
            jdbc.update("UPDATE voucher_line SET credit = credit - 1.00 WHERE id = ?", offsetLineId);
            jdbc.update("UPDATE voucher SET total_amount = total_amount - 1.00 WHERE id = ?", voucherId);
        }
    }

    // ---------------------------------------------------------------
    // 工具：按报表行名取金额（找不到则断言失败，避免静默 null）
    // ---------------------------------------------------------------

    /** 利润表某行本期金额。 */
    private static String currentOf(IncomeStatement is, String name) {
        return is.lines().stream()
                .filter(l -> l.name().equals(name))
                .map(IncomeStatementLine::currentPeriod)
                .findFirst()
                .orElseThrow(() -> new AssertionError("利润表缺行: " + name));
    }

    /** 利润表某行本年累计金额。 */
    private static String ytdOf(IncomeStatement is, String name) {
        return is.lines().stream()
                .filter(l -> l.name().equals(name))
                .map(IncomeStatementLine::yearToDate)
                .findFirst()
                .orElseThrow(() -> new AssertionError("利润表缺行: " + name));
    }

    /** 资产负债表资产行金额。 */
    private static String assetLine(BalanceSheet bs, String name) {
        return lineAmount(bs.assetLines(), name);
    }

    /** 资产负债表负债行金额。 */
    private static String liabilityLine(BalanceSheet bs, String name) {
        return lineAmount(bs.liabilityLines(), name);
    }

    /** 资产负债表权益行金额。 */
    private static String equityLine(BalanceSheet bs, String name) {
        return lineAmount(bs.equityLines(), name);
    }

    private static String lineAmount(List<BalanceSheetLine> lines, String name) {
        return lines.stream()
                .filter(l -> l.name().equals(name))
                .map(BalanceSheetLine::amount)
                .findFirst()
                .orElseThrow(() -> new AssertionError("资产负债表缺行: " + name));
    }
}
