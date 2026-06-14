package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
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
import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableRepository;
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
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.persistence.JdbcSequenceProvider;

/**
 * 业务单据红冲整链端到端集成测试（M4-T07b 验收总成，Testcontainers 真实 MySQL）。
 *
 * <p>覆盖采购入库/发票 + 销售出库/发票四类业务单据红冲在<b>同一真库环境</b>下的全链路一致性闭环，
 * 是 T07b 采购线/销售线两个并行交付的端到端整合验收（各自的
 * {@link PurchaseReversalFlowIntegrationTest} / {@code SalesReversalFlowIntegrationTest} 侧重单线深度，
 * 本类侧重四线<b>交叉勾稽 0 ERROR</b>、账龄剔除、闭月回滚与审计的整合）。
 *
 * <p>装配蓝本：{@link PurchaseToSalesFlowIntegrationTest}（整栈业务流） +
 * {@link VoucherReversalFlowIntegrationTest}（T07a 红冲装配）。用生产同套装配
 * （@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} + {@link PurchaseInfraConfig} +
 * {@link SalesInfraConfig} + {@link GlInfraConfig}）+ 把四个业务 AppService /
 * {@link VoucherAppService} 注册为 Bean（受 @Transactional 代理，等价生产边界；
 * {@link WarehouseService}/{@link SupplierService} 注入 mock——reverse 路径不触档案）。
 *
 * <h2>测试流（用 {@code YearMonth.now(UTC)} 派生账期——销售出库 COGS 凭证日 = now(UTC)）</h2>
 * <ol>
 *   <li>① 开账期 → 采购下单/审核/收货/入库 post（自动凭证）→ 采购发票 post（应付 + 自动凭证）
 *       → 销售下单/审核/出库 post（SALES_OUT + COGS 自动凭证）→ 销售发票 post（应收 + 自动凭证）；</li>
 *   <li>② 采购入库 reverse：库存按原成本归位（数量/金额）、PO.receivedQty 回滚、入库自动凭证红冲
 *       （1405/220201 source 维度净额归零）、原单 REVERSED；</li>
 *   <li>③ 销售出库 reverse：库存按原 COGS 归位、SO.deliveredQty 回滚、COGS 凭证红冲、原单 REVERSED；</li>
 *   <li>④ 采购/销售发票 reverse：AP/AR markReversed（账龄不再含该笔）、PR/SD.invoicedQty 回滚、
 *       发票自动凭证红冲、原单 REVERSED；</li>
 *   <li>⑤ 一致性 {@link ConsistencyCheckService#check()} 红冲后本链路键 0 ERROR（REVERSED 子账/发票被跳过）；</li>
 *   <li>⑥ 已核销发票 reverse 被拒（{@code canBeReversed()=false}，需 T07c 收付款冲销前置——
 *       本测构造一条已核销 AP 验证拒绝且回滚后 AP 仍 SETTLED）；</li>
 *   <li>⑦ 闭月 reverse 被拒回滚（关账后 reverse → {@link PeriodClosedException}、库存/子账/AP 无残留变更）；</li>
 *   <li>⑧ 审计：四类 *.reverse 动作均落 audit_log。</li>
 * </ol>
 *
 * <p>红冲数学（设计真源 §1.6/§1.10）：库存按<b>固化原成本</b>反向（期间已进新货改变加权仍按原值），
 * 自动凭证红冲 = 原 + 红字借贷对调，source 维度科目净额抵平归零（APPROVED+REVERSED 口径）。
 * 金额一律 {@code isEqualByComparingTo}。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class BusinessDocReversalFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

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
    private static PurchaseReceiptAppService receiptAppService;
    private static PurchaseInvoiceAppService invoiceAppService;
    private static SalesDeliveryAppService deliveryAppService;
    private static SalesInvoiceAppService salesInvoiceAppService;
    private static AccountsPayableRepository payableRepository;
    private static AccountingPeriodService accountingPeriodService;
    private static AutoVoucherService autoVoucherService;
    private static ConsistencyCheckService consistencyCheckService;
    private static AgingReportDao agingReportDao;

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
        receiptAppService = context.getBean(PurchaseReceiptAppService.class);
        invoiceAppService = context.getBean(PurchaseInvoiceAppService.class);
        deliveryAppService = context.getBean(SalesDeliveryAppService.class);
        salesInvoiceAppService = context.getBean(SalesInvoiceAppService.class);
        payableRepository = context.getBean(AccountsPayableRepository.class);
        accountingPeriodService = context.getBean(AccountingPeriodService.class);
        autoVoucherService = context.getBean(AutoVoucherService.class);
        consistencyCheckService = context.getBean(ConsistencyCheckService.class);
        agingReportDao = context.getBean(AgingReportDao.class);
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
            SalesInfraConfig.class, GlInfraConfig.class})
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
        SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
            return new JdbcSequenceProvider(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
            return new DefaultDocumentNumberGenerator(sequenceProvider);
        }

        /** reverse 路径不触仓库/供应商档案：注入 mock（建单经领域服务直驱绕开档案校验）。 */
        @Bean
        WarehouseService warehouseService() {
            return mock(WarehouseService.class);
        }

        @Bean
        SupplierService supplierService() {
            return mock(SupplierService.class);
        }

        @Bean
        VoucherAppService voucherAppService(VoucherService voucherService,
                                            DocumentNumberGenerator documentNumberGenerator) {
            return new VoucherAppService(voucherService, documentNumberGenerator);
        }

        @Bean
        PurchaseReceiptAppService purchaseReceiptAppService(
                PurchaseReceiptService purchaseReceiptService, WarehouseService warehouseService,
                DocumentNumberGenerator documentNumberGenerator, AutoVoucherService autoVoucherService,
                VoucherService voucherService, VoucherAppService voucherAppService) {
            return new PurchaseReceiptAppService(purchaseReceiptService, warehouseService,
                    documentNumberGenerator, autoVoucherService, voucherService, voucherAppService);
        }

        @Bean
        PurchaseInvoiceAppService purchaseInvoiceAppService(
                PurchaseInvoiceService purchaseInvoiceService,
                PurchaseReceiptService purchaseReceiptService,
                PurchaseOrderService purchaseOrderService,
                SupplierService supplierService,
                AccountsPayableRepository accountsPayableRepository,
                DocumentNumberGenerator documentNumberGenerator,
                AutoVoucherService autoVoucherService, VoucherService voucherService,
                VoucherAppService voucherAppService) {
            return new PurchaseInvoiceAppService(purchaseInvoiceService, purchaseReceiptService,
                    purchaseOrderService, supplierService, accountsPayableRepository,
                    documentNumberGenerator, autoVoucherService, voucherService, voucherAppService);
        }

        @Bean
        SalesDeliveryAppService salesDeliveryAppService(
                SalesDeliveryService salesDeliveryService, WarehouseService warehouseService,
                DocumentNumberGenerator documentNumberGenerator, AutoVoucherService autoVoucherService,
                VoucherService voucherService, VoucherAppService voucherAppService) {
            return new SalesDeliveryAppService(salesDeliveryService, warehouseService,
                    documentNumberGenerator, autoVoucherService, voucherService, voucherAppService);
        }

        @Bean
        SalesInvoiceAppService salesInvoiceAppService(
                SalesInvoiceService salesInvoiceService, SalesDeliveryService salesDeliveryService,
                SalesOrderService salesOrderService, DocumentNumberGenerator documentNumberGenerator,
                AutoVoucherService autoVoucherService, VoucherService voucherService,
                VoucherAppService voucherAppService) {
            return new SalesInvoiceAppService(salesInvoiceService, salesDeliveryService,
                    salesOrderService, documentNumberGenerator, autoVoucherService, voucherService,
                    voucherAppService);
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

        @Bean
        AgingReportDao agingReportDao(JdbcTemplate jdbcTemplate) {
            return new AgingReportDao(jdbcTemplate);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // =====================================================================
    // 主链：四类业务单据红冲整链 + 交叉一致性 0 ERROR + 账龄剔除 + 审计
    // =====================================================================

    @Test
    void 四类业务单据红冲整链_库存按原值归位_子账与凭证冲销_账龄剔除_一致性0ERROR_审计() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-BR-" + suffix;
        String prNo = "PR-BR-" + suffix;
        String pinvNo = "PINV-BR-" + suffix;
        String soNo = "SO-BR-" + suffix;
        String sdNo = "SD-BR-" + suffix;
        String sinvNo = "SINV-BR-" + suffix;

        // 账期用 now(UTC) 派生——销售出库 COGS 自动凭证日 = now(UTC)，须该期 OPEN 才能过账/红冲
        YearMonth ymNow = YearMonth.now(ZoneOffset.UTC);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // 业务单据日用今天（采购入库/发票自动凭证按业务单日落账期，统一今天避免跨期）
        LocalDate d = today;
        String period = ymNow.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));

        txTemplate.executeWithoutResult(s -> accountingPeriodService.open(period, OPERATOR));

        // ---- ① 采购线：下单 100@12.50 → 审核 → 收 100 → 入库 post（自动凭证 借1405/贷220201 各 1250） ----
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "整链采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d,
                "收货", List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        receiptAppService.post(prNo, OPERATOR);   // AppService 自身 @Transactional + 自动凭证

        // 采购发票 100@1250.00 → 审核 → 过账（应付 1250.00 + 自动凭证 借220201/贷220202）
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-BR", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            var invoice = purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR);
            autoVoucherService.generateForPurchaseInvoice(invoice, OPERATOR);
        });

        // ---- 销售线：下单 60@20 → 审核 → 出 60 post（SALES_OUT，COGS=60×12.50=750.00，库存→40/500.00） ----
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, d, "整链销售",
                List.of(new SalesOrderLineInput(productId, new BigDecimal("60"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(soNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(sdNo, soNo, warehouseId, "发货",
                List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("60"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(sdNo, OPERATOR));
        deliveryAppService.post(sdNo, OPERATOR);   // AppService 自身 @Transactional + COGS 自动凭证

        // 销售发票 60@25 → 审核 → 过账（应收 1500.00 + 自动凭证 借1122/贷6001）
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(sinvNo, sdNo, customerId, d,
                d.plusMonths(1), "开票",
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("60"),
                        new BigDecimal("25"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(sinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            var invoice = salesInvoiceService.post(sinvNo, OPERATOR);
            autoVoucherService.generateForSalesInvoice(invoice, OPERATOR);
        });

        // 业务态：库存 40/500.00；应付/应收 OPEN；COGS=750；账龄含双方
        assertBalanceQty(warehouseId, productId, "40");
        assertBalanceAmount(warehouseId, productId, "500.00");
        assertThat(payableStatus(pinvNo)).isEqualTo("OPEN");
        assertThat(receivableStatus(sinvNo)).isEqualTo("OPEN");
        assertThat(cogsOf(sdNo, 1)).isEqualByComparingTo("750.00");
        assertThat(payableInAging(supplierId)).as("红冲前应付在账龄").isTrue();
        assertThat(receivableInAging(customerId)).as("红冲前应收在账龄").isTrue();

        // ---- ④a 销售发票 reverse：应收 REVERSED + 回退 SD.invoicedQty + 红冲发票凭证 + 原单 REVERSED ----
        salesInvoiceAppService.reverse(sinvNo, OPERATOR);
        assertThat(docStatus("sales_invoice", sinvNo)).isEqualTo("REVERSED");
        assertThat(receivableStatus(sinvNo)).isEqualTo("REVERSED");
        assertThat(invoicedQty("sales_delivery_line", "sales_delivery", sdNo, 1))
                .as("出库行已开票量回退为 0").isEqualByComparingTo("0");
        assertSourceNetZero("1122", sinvNo);   // 应收 / 收入凭证抵平
        assertThat(receivableInAging(customerId)).as("应收红冲后剔出账龄").isFalse();

        // ---- ③ 销售出库 reverse：库存按原 COGS 归位 + 回退 SO.deliveredQty + COGS 凭证红冲 + 原单 REVERSED ----
        deliveryAppService.reverse(sdNo, OPERATOR);
        assertThat(docStatus("sales_delivery", sdNo)).isEqualTo("REVERSED");
        // 库存归位：40 + 60 = 100；金额 500 + 750(原 COGS) = 1250
        assertBalanceQty(warehouseId, productId, "100");
        assertBalanceAmount(warehouseId, productId, "1250.00");
        // 反向入库流水按原 COGS 单价（750/60=12.50）：total_cost = +750.00
        assertThat(reversalTxnCost(sdNo, 1)).as("出库红冲按原 COGS 反向入库")
                .isEqualByComparingTo("750.00");
        // SO 累计发货量回退为 0
        assertThat(salesOrderService.get(soNo).getLines().get(0).getDeliveredQty())
                .isEqualByComparingTo("0");
        assertSourceNetZero("6401", sdNo);   // COGS / 库存凭证抵平

        // ---- ④b 采购发票 reverse：应付 REVERSED + 回退 PR.invoicedQty + 红冲发票凭证 + 原单 REVERSED ----
        invoiceAppService.reverse(pinvNo, OPERATOR);
        assertThat(docStatus("purchase_invoice", pinvNo)).isEqualTo("REVERSED");
        assertThat(payableStatus(pinvNo)).isEqualTo("REVERSED");
        assertThat(invoicedQty("purchase_receipt_line", "purchase_receipt", prNo, 1))
                .as("收货行已开票量回退为 0").isEqualByComparingTo("0");
        assertSourceNetZero("220202", pinvNo);
        assertThat(payableInAging(supplierId)).as("应付红冲后剔出账龄").isFalse();

        // ---- ② 采购入库 reverse：库存按原成本 12.50 归位 + 回退 PO.receivedQty + 红冲入库凭证 + 原单 REVERSED ----
        receiptAppService.reverse(prNo, OPERATOR);
        assertThat(docStatus("purchase_receipt", prNo)).isEqualTo("REVERSED");
        // 库存回到入库前：100 - 100 = 0；金额 1250 - 1250 = 0.00
        assertBalanceQty(warehouseId, productId, "0");
        assertBalanceAmount(warehouseId, productId, "0.00");
        // 反向出库流水按原单价 12.50×100：total_cost = -1250.00
        assertThat(reversalTxnCost(prNo, 1)).as("入库红冲按原成本反向出库")
                .isEqualByComparingTo("-1250.00");
        // PO 到货量回退为 0
        assertThat(purchaseOrderService.get(poNo).getLines().get(0).getReceivedQty())
                .isEqualByComparingTo("0");
        assertSourceNetZero("1405", prNo);

        // ---- ⑤ 一致性 check()：本链路相关键 0 ERROR（REVERSED 子账/发票被跳过、库存账实归位） ----
        ConsistencyReport report = consistencyCheckService.check();
        String invKey = "warehouse=" + warehouseId + ",product=" + productId;
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                // 含核销 rollup break 键（形如 PINV-xxx#PAYABLE#N / SINV-xxx#RECEIVABLE#N，
                // 见 ConsistencyCheckService.checkSettlementRollup：sourceDocNo#type#targetId）——
                // 必须纳入断言范围，否则红冲后 REVERSED 子账的 rollup ERROR 会被静默滤掉、绿测掩盖缺陷（评审 P2）
                .filter(b -> b.key() != null && (b.key().contains(invKey)
                        || b.key().equals(pinvNo) || b.key().equals(sinvNo)
                        || b.key().startsWith(pinvNo + "#") || b.key().startsWith(sinvNo + "#")
                        || b.key().startsWith(sdNo + "#") || b.key().startsWith(prNo + "#")
                        || b.key().equals(poNo) || b.key().equals(soNo)
                        || b.key().contains(poNo) || b.key().contains(soNo)))
                .toList();
        assertThat(errors)
                .as("四类业务单据红冲后本链路相关键应 0 个 ERROR break，实际：%s", errors)
                .isEmpty();

        // ---- ⑧ 审计：四类 *.reverse 动作均落 audit_log ----
        assertThat(reverseAuditCount("purchase_receipt.reverse", prNo))
                .as("采购入库红冲审计").isGreaterThanOrEqualTo(1L);
        assertThat(reverseAuditCount("purchase_invoice.reverse", pinvNo))
                .as("采购发票红冲审计").isGreaterThanOrEqualTo(1L);
        assertThat(reverseAuditCount("sales_delivery.reverse", sdNo))
                .as("销售出库红冲审计").isGreaterThanOrEqualTo(1L);
        assertThat(reverseAuditCount("sales_invoice.reverse", sinvNo))
                .as("销售发票红冲审计").isGreaterThanOrEqualTo(1L);
    }

    // =====================================================================
    // 红冲按固化原值（期间进新货改变加权）+ 幂等（原单已 REVERSED 再 reverse 被拒）
    // =====================================================================

    @Test
    void 入库红冲按原值反向_期间进新货改变加权仍按原值_幂等再冲被拒() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-W-" + suffix;
        String prNo = "PR-W-" + suffix;
        String po2 = "PO2-W-" + suffix;
        String pr2 = "PR2-W-" + suffix;
        YearMonth ymNow = YearMonth.now(ZoneOffset.UTC);
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        String period = ymNow.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));

        // 账期可能已被前测开启，幂等 open（已 open 时 service 容忍/或直查）——直接尝试 open，失败说明已开
        ensurePeriodOpen(period);

        // 入库 100@12.50（库存 100/1250.00）
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, null,
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d,
                null, List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        receiptAppService.post(prNo, OPERATOR);

        // 期间再进 50@20（库存 150/2250.00，加权单价 15）——验证红冲按原值 12.50 而非 15
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(po2, supplierId, d, null,
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("50"),
                        new BigDecimal("20"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(po2, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(pr2, po2, warehouseId, d,
                null, List.of(new PurchaseReceiptLineInput(1, new BigDecimal("50"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(pr2, OPERATOR));
        receiptAppService.post(pr2, OPERATOR);
        assertBalanceQty(warehouseId, productId, "150");
        assertBalanceAmount(warehouseId, productId, "2250.00");

        // 红冲第一张入库：按原值 12.50×100=1250 反向（2250 → 1000，而非按加权 15 反向）
        receiptAppService.reverse(prNo, OPERATOR);
        assertBalanceQty(warehouseId, productId, "50");
        assertBalanceAmount(warehouseId, productId, "1000.00");
        assertThat(reversalTxnCost(prNo, 1)).as("反向出库按原成本 12.50×100")
                .isEqualByComparingTo("-1250.00");

        // 幂等：原单已 REVERSED 再 reverse 被拒（领域层 IllegalState → 整事务回滚）
        assertThatThrownBy(() -> receiptAppService.reverse(prNo, OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        // 状态/库存不变（回滚无副作用）
        assertThat(docStatus("purchase_receipt", prNo)).isEqualTo("REVERSED");
        assertBalanceQty(warehouseId, productId, "50");
        assertBalanceAmount(warehouseId, productId, "1000.00");
    }

    // =====================================================================
    // ⑥ 已核销发票 reverse 被拒（canBeReversed=false）+ 回滚后 AP 仍 SETTLED
    // =====================================================================

    @Test
    void 已核销应付的发票reverse被拒_回滚后应付仍SETTLED() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-S-" + suffix;
        String prNo = "PR-S-" + suffix;
        String pinvNo = "PINV-S-" + suffix;
        YearMonth ymNow = YearMonth.now(ZoneOffset.UTC);
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        String period = ymNow.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        ensurePeriodOpen(period);

        // 入库 + 发票（应付 OPEN 500.00）
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, null,
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("5"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d,
                null, List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        receiptAppService.post(prNo, OPERATOR);
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, null, null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("500.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            var invoice = purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR);
            autoVoucherService.generateForPurchaseInvoice(invoice, OPERATOR);
        });

        // 构造已核销 AP：装载 → settle 全额 → save（模拟 T07c 之前已有核销，使 canBeReversed=false）
        txTemplate.executeWithoutResult(s -> {
            AccountsPayable ap = payableRepository.findBySourceDocNo(pinvNo).get(0);
            ap.settle(new BigDecimal("500.00"));
            payableRepository.save(ap);
        });
        assertThat(payableStatus(pinvNo)).isEqualTo("SETTLED");

        // 已核销发票 reverse 前置拒绝（IllegalState「请先冲对应付款单」）
        assertThatThrownBy(() -> invoiceAppService.reverse(pinvNo, OPERATOR))
                .isInstanceOf(IllegalStateException.class);

        // 回滚后：发票仍 COMPLETED、AP 仍 SETTLED、收货行开票量不变、无红字凭证残留
        assertThat(docStatus("purchase_invoice", pinvNo)).isEqualTo("COMPLETED");
        assertThat(payableStatus(pinvNo)).isEqualTo("SETTLED");
        assertThat(invoicedQty("purchase_receipt_line", "purchase_receipt", prNo, 1))
                .isEqualByComparingTo("100");
        assertThat(reversalVoucherCountForSource(pinvNo, "PURCHASE_INVOICE"))
                .as("被拒后无红字凭证残留").isZero();
    }

    // =====================================================================
    // ⑦ 闭月 reverse 被拒回滚（库存/子账/AP 无残留变更）
    // =====================================================================

    @Test
    void 闭月红冲被拒_整事务回滚_库存与单据状态不变() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-CL-" + suffix;
        String prNo = "PR-CL-" + suffix;
        // 用独立历史账期 202602，关账后红冲入库凭证须在该期过账被拒（不与 now() 账期冲突）
        String period = "202602";
        LocalDate d = LocalDate.of(2026, 2, 10);

        ensurePeriodOpen(period);

        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, null,
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("40"),
                        new BigDecimal("5"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d,
                null, List.of(new PurchaseReceiptLineInput(1, new BigDecimal("40"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        receiptAppService.post(prNo, OPERATOR);   // 入库 + 自动凭证（账期 202602）

        // 关账 202602 → 红冲入库凭证需在该账期过账被拒
        txTemplate.executeWithoutResult(s -> accountingPeriodService.close(period, OPERATOR));
        assertThat(accountingPeriodService.isOpen(period)).isFalse();

        assertThatThrownBy(() -> receiptAppService.reverse(prNo, OPERATOR))
                .isInstanceOf(PeriodClosedException.class);

        // 整事务回滚：原单仍 COMPLETED、库存数量未变、未生成红字凭证、PO 到货量不变
        assertThat(docStatus("purchase_receipt", prNo)).isEqualTo("COMPLETED");
        assertBalanceQty(warehouseId, productId, "40");
        assertBalanceAmount(warehouseId, productId, "200.00");
        assertThat(purchaseOrderService.get(poNo).getLines().get(0).getReceivedQty())
                .isEqualByComparingTo("40");
        assertThat(reversalVoucherCountForSource(prNo, "PURCHASE_RECEIPT"))
                .as("闭月回滚：无红字凭证").isZero();
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    /** 账期幂等开启：未开则 open（已开则吞 IllegalState/重复异常，保各用例独立可重入）。 */
    private void ensurePeriodOpen(String period) {
        if (!accountingPeriodService.isOpen(period)) {
            try {
                txTemplate.executeWithoutResult(s -> accountingPeriodService.open(period, OPERATOR));
            } catch (RuntimeException ignore) {
                // 并发/已存在：忽略，下方业务过账会再以 isOpen 兜底
            }
        }
    }

    private BigDecimal balanceQty(long warehouseId, long productId) {
        return jdbc.queryForObject("SELECT quantity FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                BigDecimal.class, warehouseId, productId);
    }

    private void assertBalanceQty(long warehouseId, long productId, String expected) {
        assertThat(balanceQty(warehouseId, productId)).as("商品 %d 余额数量", productId)
                .isEqualByComparingTo(expected);
    }

    private void assertBalanceAmount(long warehouseId, long productId, String expected) {
        BigDecimal amount = jdbc.queryForObject("SELECT cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                BigDecimal.class, warehouseId, productId);
        assertThat(amount).as("商品 %d 余额金额", productId).isEqualByComparingTo(expected);
    }

    private String docStatus(String table, String docNo) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE doc_no = ?",
                String.class, docNo);
    }

    private String payableStatus(String invoiceNo) {
        return jdbc.queryForObject("SELECT status FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", String.class, invoiceNo);
    }

    private String receivableStatus(String invoiceNo) {
        return jdbc.queryForObject("SELECT status FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", String.class, invoiceNo);
    }

    private BigDecimal cogsOf(String deliveryNo, int lineNo) {
        return jdbc.queryForObject("SELECT cogs_amount FROM sales_delivery_line "
                + "WHERE sales_delivery_id = (SELECT id FROM sales_delivery WHERE doc_no = ?) "
                + "AND line_no = ?", BigDecimal.class, deliveryNo, lineNo);
    }

    /** 子账行回退后的已开票量（采购收货行 / 销售出库行通用，按 line_no 取）。 */
    private BigDecimal invoicedQty(String lineTable, String headerTable, String docNo, int lineNo) {
        String fk = headerTable + "_id";
        return jdbc.queryForObject("SELECT invoiced_qty FROM " + lineTable
                + " WHERE " + fk + " = (SELECT id FROM " + headerTable + " WHERE doc_no = ?) "
                + "AND line_no = ?", BigDecimal.class, docNo, lineNo);
    }

    /** 某业务单某行红冲流水的 total_cost（幂等键 REVERSAL:<单号>:<行号>）。 */
    private BigDecimal reversalTxnCost(String docNo, int lineNo) {
        return jdbc.queryForObject("SELECT total_cost FROM inventory_transaction "
                + "WHERE tenant_id = 0 AND idempotency_key = ?", BigDecimal.class,
                "REVERSAL:" + docNo + ":" + lineNo);
    }

    /**
     * 某科目在「某来源单据的自动凭证 + 其红字凭证」上净额归零（借−贷 跨原+红字抵平，含 REVERSED 原凭证）。
     */
    private void assertSourceNetZero(String accountCode, String sourceDocNo) {
        String autoVoucherNo = jdbc.queryForObject("SELECT doc_no FROM voucher WHERE tenant_id = 0 "
                + "AND source_doc_no = ? AND source_doc_type <> 'VOUCHER_REVERSAL' LIMIT 1",
                String.class, sourceDocNo);
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(vl.debit - vl.credit), 0) FROM voucher_line vl "
                        + "JOIN voucher v ON v.id = vl.voucher_id "
                        + "WHERE v.tenant_id = 0 AND vl.account_code = ? "
                        + "AND (v.source_doc_no = ? OR v.source_doc_no = ?)",
                BigDecimal.class, accountCode, sourceDocNo, autoVoucherNo);
        assertThat(net).as("科目 %s 跨原+红字凭证净额归零", accountCode).isEqualByComparingTo("0");
    }

    /** 以某业务单为来源的某类型自动凭证的红字凭证条数（红字 source_doc_no = 自动凭证号）。 */
    private Long reversalVoucherCountForSource(String sourceDocNo, String autoType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM voucher WHERE tenant_id = 0 "
                + "AND source_doc_type = 'VOUCHER_REVERSAL' AND source_doc_no IN "
                + "(SELECT doc_no FROM voucher WHERE tenant_id = 0 AND source_doc_no = ? "
                + "AND source_doc_type = ?)", Long.class, sourceDocNo, autoType);
    }

    private Long reverseAuditCount(String action, String targetCode) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target_code = ?",
                Long.class, action, targetCode);
    }

    /** 该供应商在应付账龄（截止今天）是否仍有行（红冲后应被剔除）。 */
    private boolean payableInAging(long supplierId) {
        var report = agingReportDao.payableAging(LocalDate.now(ZoneOffset.UTC), supplierId, 1,
                AgingReportDao.MAX_SIZE);
        return !report.page().items().isEmpty();
    }

    /** 该客户在应收账龄（截止今天）是否仍有行（红冲后应被剔除）。 */
    private boolean receivableInAging(long customerId) {
        var report = agingReportDao.receivableAging(LocalDate.now(ZoneOffset.UTC), customerId, 1,
                AgingReportDao.MAX_SIZE);
        return !report.page().items().isEmpty();
    }
}
