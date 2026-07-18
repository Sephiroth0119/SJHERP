package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
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

import com.sjherp.app.collection.CollectionDtos.CollectionReceiptLineRequest;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.consistency.ConsistencyBreak;
import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.consistency.ConsistencyReport;
import com.sjherp.app.consistency.ConsistencySeverity;
import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.VoucherService;
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
import com.sjherp.infra.persistence.JdbcSequenceProvider;

/**
 * 收/付款单红冲 + 核销反向 + 解锁发票红冲 端到端真库集成测试（M4-T07c 验收核心，Testcontainers 真实 MySQL，
 * 设计真源 docs/M4拆解-统一冲销机制.md §66-73）。
 *
 * <p>装配蓝本：{@link CollectionPaymentFlowIntegrationTest}（收/付款单全套现金侧装配）+
 * {@link BusinessDocReversalFlowIntegrationTest}（一致性 / 账龄 / ensurePeriodOpen 幂等开账）。
 * 额外把 {@link SalesInvoiceAppService} 注册为 Bean——验证「先冲收款单 → 应收 settled 回 0 →
 * {@code canBeReversed()=true} → 再红冲销售发票成功」这条 T07c 解锁链路（设计真源 §73）。
 *
 * <h2>四组验收</h2>
 * <ol>
 *   <li><b>收款单红冲整链 + 解锁销售发票</b>：销售发票生成应收(1500) → 收款单 post（核销应收 SETTLED +
 *       现金侧凭证 借1002/贷1122）→ 收款单 reverse：应收 settled 回退(SETTLED→OPEN)、负额反向核销记录、
 *       现金侧凭证红冲(净额归零，原 REVERSED + 红字 APPROVED)、收款单 REVERSED；
 *       再红冲该销售发票成功（应收 markReversed、出库行开票量回退）；</li>
 *   <li><b>付款单红冲（对称）</b>：采购发票生成应付(1250) → 付款单 post（核销应付 SETTLED + 借220202/贷1002）→
 *       付款单 reverse：应付回退、负额记录、现金侧凭证红冲、付款单 REVERSED；</li>
 *   <li><b>幂等</b>：已 REVERSED 的收/付款单再 reverse 被拒（领域层 IllegalState）、闭月红冲被拒回滚；</li>
 *   <li><b>一致性 + 审计</b>：reverse 后核销 rollup 含负额记录 Σ==settled，{@link ConsistencyCheckService#check()}
 *       本链路 0 ERROR（含规则 8/9/10）；审计落 {@code *.reverse} / {@code settlement.unsettle.*}。</li>
 * </ol>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class SettlementReversalFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-setrev";

    /** 银行存款 1002：V19 预置末级启用科目，可作资金账户 glAccountCode（现金侧借/贷科目）。 */
    private static final String GL_BANK = "1002";

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
    private static PaymentAccountService paymentAccountService;
    private static CollectionReceiptAppService collectionReceiptAppService;
    private static com.sjherp.app.payment.PaymentDisbursementAppService paymentDisbursementAppService;
    private static SalesInvoiceAppService salesInvoiceAppService;
    private static AutoVoucherService autoVoucherService;
    private static VoucherService voucherService;
    private static AccountingPeriodService accountingPeriodService;
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
        paymentAccountService = context.getBean(PaymentAccountService.class);
        collectionReceiptAppService = context.getBean(CollectionReceiptAppService.class);
        paymentDisbursementAppService =
                context.getBean(com.sjherp.app.payment.PaymentDisbursementAppService.class);
        salesInvoiceAppService = context.getBean(SalesInvoiceAppService.class);
        autoVoucherService = context.getBean(AutoVoucherService.class);
        voucherService = context.getBean(VoucherService.class);
        accountingPeriodService = context.getBean(AccountingPeriodService.class);
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
            SalesInfraConfig.class, GlInfraConfig.class, ProductRepositoryTestConfig.class, SettlementInfraConfig.class,
            FundInfraConfig.class, CollectionInfraConfig.class, PaymentInfraConfig.class})
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

        @Bean
        VoucherAppService voucherAppService(VoucherService voucherService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new VoucherAppService(voucherService, documentNumberGenerator);
        }

        // 收款单 / 付款单 AppService（生产是 @Service 组件，本上下文无组件扫描，显式装配）
        @Bean
        CollectionReceiptAppService collectionReceiptAppService(
                com.sjherp.domain.collection.CollectionReceiptService collectionReceiptService,
                PaymentAccountService paymentAccountService,
                com.sjherp.domain.receivable.ReceivableService receivableService,
                com.sjherp.domain.settlement.SettlementService settlementService,
                com.sjherp.domain.settlement.SettlementRecordRepository settlementRecordRepository,
                AutoVoucherService autoVoucherService,
                VoucherService voucherService,
                VoucherAppService voucherAppService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new CollectionReceiptAppService(collectionReceiptService, paymentAccountService,
                    receivableService, settlementService, settlementRecordRepository, autoVoucherService,
                    voucherService, voucherAppService, documentNumberGenerator);
        }

        @Bean
        com.sjherp.app.payment.PaymentDisbursementAppService paymentDisbursementAppService(
                com.sjherp.domain.payment.PaymentDisbursementService paymentDisbursementService,
                PaymentAccountService paymentAccountService,
                com.sjherp.domain.payable.AccountsPayableRepository payableRepository,
                com.sjherp.domain.settlement.SettlementService settlementService,
                com.sjherp.domain.settlement.SettlementRecordRepository settlementRecordRepository,
                AutoVoucherService autoVoucherService,
                VoucherService voucherService,
                VoucherAppService voucherAppService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new com.sjherp.app.payment.PaymentDisbursementAppService(paymentDisbursementService,
                    paymentAccountService, payableRepository, settlementService, settlementRecordRepository,
                    autoVoucherService, voucherService, voucherAppService, documentNumberGenerator);
        }

        // 销售发票 AppService（验证解锁链路：先冲收款单 → 再红冲销售发票）；reverse 路径不触档案，
        // 故无需 WarehouseService/SupplierService 真实档案，但 SalesInvoiceAppService 构造不需档案。
        @Bean
        SalesInvoiceAppService salesInvoiceAppService(SalesInvoiceService salesInvoiceService,
                SalesDeliveryService salesDeliveryService, SalesOrderService salesOrderService,
                DocumentNumberGenerator documentNumberGenerator, AutoVoucherService autoVoucherService,
                VoucherService voucherService, VoucherAppService voucherAppService) {
            return new SalesInvoiceAppService(salesInvoiceService, salesDeliveryService,
                    salesOrderService, documentNumberGenerator, autoVoucherService, voucherService,
                    voucherAppService);
        }

        // 一致性校验单元（allow-negative=false，与生产一致）
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
    // 验收①：收款单红冲整链 + 解锁销售发票红冲 + 核销 rollup 含负额一致 + 审计
    // =====================================================================

    @Test
    void 收款单红冲_应收回退现金凭证净额归零_解锁销售发票红冲_一致性0ERROR_审计() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String sinvNo = "SINV-SR-" + suffix;

        YearMonth ymNow = YearMonth.now(ZoneOffset.UTC);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String period = ymNow.format(DateTimeFormatter.ofPattern("yyyyMM"));
        ensurePeriodOpen(period);

        // 销售整链过账生成应收 1500.00（业务日 = today，落 now(UTC) 账期，使现金侧/红冲落同期可试算平衡）
        long arId = createReceivableViaInvoicePosting(supplierId, warehouseId, productId, customerId,
                suffix, sinvNo, today);
        assertSubledgerReceivable(arId, "1500.00", "0.00", "OPEN");

        long acctId = createPaymentAccount("收款用银行账户-" + suffix);

        // ---- 收款单 post：全额核销应收 SETTLED + 现金侧凭证 借1002/贷1122 ----
        long unsettleAuditBefore = auditCount("settlement.unsettle.receivable");
        String rcptNo = createApproveCollectionAndPost(() ->
                collectionReceiptAppService.create(customerId, acctId, today, "全额收款",
                        List.of(new CollectionReceiptLineRequest(arId, new BigDecimal("1500.00"))),
                        OPERATOR).getDocNo());
        assertThat(rcptNo).startsWith("RCPT-");
        assertSubledgerReceivable(arId, "1500.00", "1500.00", "SETTLED");
        assertThat(voucherService.findBySourceDocNo(rcptNo)).as("收款现金侧凭证一组").hasSize(1);

        // ---- 收款单 reverse：应收回退 SETTLED→OPEN、负额反向核销记录、现金侧凭证红冲、收款单 REVERSED ----
        long reverseAuditBefore = auditCount("collection_receipt.reverse");
        txTemplate.executeWithoutResult(s -> collectionReceiptAppService.reverse(rcptNo, OPERATOR));

        // 应收 settled 回退到 0、状态回 OPEN
        assertSubledgerReceivable(arId, "1500.00", "0.00", "OPEN");
        // 收款单 → REVERSED
        assertThat(docStatus("collection_receipt", rcptNo)).isEqualTo("REVERSED");
        // 负额反向核销记录：正 1500 + 负 1500，Σ = 0 == settled（不变式）
        assertThat(settlementSum("RECEIVABLE", arId)).as("Σ核销记录 = 子账 settled = 0")
                .isEqualByComparingTo("0");
        assertThat(settlementRecordCount("RECEIVABLE", arId)).as("正向 + 负额反向 共两笔").isEqualTo(2L);
        assertThat(negativeRecordCount("RECEIVABLE", arId)).as("一笔负额反向记录").isEqualTo(1L);
        // 现金侧凭证：原 COLLECTION_RECEIPT → REVERSED + 一张 VOUCHER_REVERSAL 红字（APPROVED），1002/1122 净额归零
        assertThat(autoVoucherStatus(rcptNo, "COLLECTION_RECEIPT")).isEqualTo("REVERSED");
        assertThat(reversalVoucherCount(rcptNo, "COLLECTION_RECEIPT")).as("一张红字凭证").isEqualTo(1L);
        assertCashSourceNetZero("1002", rcptNo);
        assertCashSourceNetZero("1122", rcptNo);
        // 审计：unsettle.receivable + collection_receipt.reverse 各 +1
        assertThat(auditCount("settlement.unsettle.receivable")).isGreaterThan(unsettleAuditBefore);
        assertThat(auditCount("collection_receipt.reverse")).isGreaterThan(reverseAuditBefore);

        // ---- 解锁：应收 settled 回 0 → canBeReversed=true → 红冲销售发票成功 ----
        assertThat(receivableInAging(customerId)).as("收款红冲后应收回到账龄").isTrue();
        salesInvoiceAppService.reverse(sinvNo, OPERATOR);
        assertThat(docStatus("sales_invoice", sinvNo)).isEqualTo("REVERSED");
        assertThat(receivableStatus(sinvNo)).as("应收 markReversed").isEqualTo("REVERSED");
        assertThat(receivableInAging(customerId)).as("发票红冲后应收剔出账龄").isFalse();

        // ---- 一致性：本链路 0 ERROR（核销 rollup 规则 8/9/10 含负额 Σ==settled；REVERSED 子账被跳过） ----
        ConsistencyReport report = consistencyCheckService.check();
        List<ConsistencyBreak> errors = report.breaks().stream()
                .filter(b -> b.severity() == ConsistencySeverity.ERROR)
                .filter(b -> b.key() != null
                        && (b.key().equals(sinvNo) || b.key().startsWith(sinvNo + "#")))
                .toList();
        assertThat(errors).as("收款红冲后本链路应 0 ERROR，实际：%s", errors).isEmpty();
    }

    // =====================================================================
    // 验收②：付款单红冲（对称）+ 闭月红冲被拒回滚
    // =====================================================================

    @Test
    void 付款单红冲_应付回退现金凭证净额归零_负额记录Σ等于settled_审计() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String pinvNo = "PINV-SR-" + suffix;

        YearMonth ymNow = YearMonth.now(ZoneOffset.UTC);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String period = ymNow.format(DateTimeFormatter.ofPattern("yyyyMM"));
        ensurePeriodOpen(period);

        long apId = createPayableViaInvoicePosting(supplierId, warehouseId, productId, suffix, pinvNo, today);
        assertSubledgerPayable(apId, "1250.00", "0.00", "OPEN");

        long acctId = createPaymentAccount("付款用银行账户-" + suffix);

        // 付款单 post：全额核销应付 SETTLED + 现金侧凭证 借220202/贷1002
        long unsettleAuditBefore = auditCount("settlement.unsettle.payable");
        String payNo = createApprovePaymentAndPost(() ->
                paymentDisbursementAppService.create(supplierId, acctId, today, "全额付款",
                        List.of(new com.sjherp.app.payment.PaymentDtos.PaymentDisbursementLineRequest(
                                apId, new BigDecimal("1250.00"))), OPERATOR).getDocNo());
        assertThat(payNo).startsWith("PAYV-");
        assertSubledgerPayable(apId, "1250.00", "1250.00", "SETTLED");

        // 付款单 reverse：应付回退 SETTLED→OPEN、负额记录、现金侧凭证红冲、付款单 REVERSED
        txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.reverse(payNo, OPERATOR));
        assertSubledgerPayable(apId, "1250.00", "0.00", "OPEN");
        assertThat(docStatus("payment_disbursement", payNo)).isEqualTo("REVERSED");
        assertThat(settlementSum("PAYABLE", apId)).as("Σ核销记录 = settled = 0").isEqualByComparingTo("0");
        assertThat(settlementRecordCount("PAYABLE", apId)).isEqualTo(2L);
        assertThat(negativeRecordCount("PAYABLE", apId)).isEqualTo(1L);
        assertThat(autoVoucherStatus(payNo, "PAYMENT_DISBURSEMENT")).isEqualTo("REVERSED");
        assertThat(reversalVoucherCount(payNo, "PAYMENT_DISBURSEMENT")).isEqualTo(1L);
        assertCashSourceNetZero("220202", payNo);
        assertCashSourceNetZero("1002", payNo);
        assertThat(auditCount("settlement.unsettle.payable")).isGreaterThan(unsettleAuditBefore);

        // 解锁后应付回到 OPEN（账龄重现）
        assertThat(payableInAging(supplierId)).as("付款红冲后应付回到账龄").isTrue();
    }

    // =====================================================================
    // 验收③：幂等——已 REVERSED 的收款单再 reverse 被拒（领域层 IllegalState）
    // =====================================================================

    @Test
    void 收款单已冲销再reverse被拒_状态保持REVERSED() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String sinvNo = "SINV-ID-" + suffix;

        YearMonth ymNow = YearMonth.now(ZoneOffset.UTC);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        ensurePeriodOpen(ymNow.format(DateTimeFormatter.ofPattern("yyyyMM")));

        long arId = createReceivableViaInvoicePosting(supplierId, warehouseId, productId, customerId,
                suffix, sinvNo, today);
        long acctId = createPaymentAccount("幂等银行账户-" + suffix);
        String rcptNo = createApproveCollectionAndPost(() ->
                collectionReceiptAppService.create(customerId, acctId, today, "收款",
                        List.of(new CollectionReceiptLineRequest(arId, new BigDecimal("1500.00"))),
                        OPERATOR).getDocNo());

        txTemplate.executeWithoutResult(s -> collectionReceiptAppService.reverse(rcptNo, OPERATOR));
        assertThat(docStatus("collection_receipt", rcptNo)).isEqualTo("REVERSED");

        // 已 REVERSED 再 reverse：领域 reverse 守门拒（IllegalState）→ 整事务回滚
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> collectionReceiptAppService.reverse(rcptNo, OPERATOR)))
                .isInstanceOf(IllegalStateException.class);
        // 状态仍 REVERSED、应收仍 OPEN/0、核销记录仍两笔（无二次反向）
        assertThat(docStatus("collection_receipt", rcptNo)).isEqualTo("REVERSED");
        assertSubledgerReceivable(arId, "1500.00", "0.00", "OPEN");
        assertThat(settlementRecordCount("RECEIVABLE", arId)).as("再冲被拒后核销记录数不变").isEqualTo(2L);
    }

    // =====================================================================
    // 验收④：闭月红冲被拒回滚（现金侧凭证红冲落关账期 → PeriodClosedException 整单回滚）
    // =====================================================================

    @Test
    void 闭月付款单红冲被拒_整事务回滚_应付与单据状态不变() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String pinvNo = "PINV-CL-" + suffix;
        // 用独立历史账期 202603，关账后红冲现金侧凭证须在该期过账被拒
        String period = "202603";
        LocalDate d = LocalDate.of(2026, 3, 12);
        ensurePeriodOpen(period);

        long apId = createPayableViaInvoicePosting(supplierId, warehouseId, productId, suffix, pinvNo, d);
        long acctId = createPaymentAccount("闭月银行账户-" + suffix);
        String payNo = createApprovePaymentAndPost(() ->
                paymentDisbursementAppService.create(supplierId, acctId, d, "闭月付款",
                        List.of(new com.sjherp.app.payment.PaymentDtos.PaymentDisbursementLineRequest(
                                apId, new BigDecimal("1250.00"))), OPERATOR).getDocNo());
        assertSubledgerPayable(apId, "1250.00", "1250.00", "SETTLED");

        // 关账 202603 → 红冲现金侧凭证需在该期过账被拒
        txTemplate.executeWithoutResult(s -> accountingPeriodService.close(period, OPERATOR));
        assertThat(accountingPeriodService.isOpen(period)).isFalse();

        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.reverse(payNo, OPERATOR)))
                .isInstanceOf(PeriodClosedException.class);

        // 整事务回滚：付款单仍 COMPLETED、应付仍 SETTLED、无负额反向记录、原凭证未被冲销
        assertThat(docStatus("payment_disbursement", payNo)).isEqualTo("COMPLETED");
        assertSubledgerPayable(apId, "1250.00", "1250.00", "SETTLED");
        assertThat(negativeRecordCount("PAYABLE", apId)).as("闭月回滚：无负额反向记录").isZero();
        assertThat(autoVoucherStatus(payNo, "PAYMENT_DISBURSEMENT")).as("原凭证未被冲销").isEqualTo("APPROVED");
        assertThat(reversalVoucherCount(payNo, "PAYMENT_DISBURSEMENT")).as("无红字凭证残留").isZero();
    }

    // ---------------------------------------------------------------
    // 建→审→过账 编排（各步独立外层事务，模拟 REST 调用边界）
    // ---------------------------------------------------------------

    private String createApproveCollectionAndPost(java.util.function.Supplier<String> createReturningDocNo) {
        String docNo = txTemplate.execute(s -> createReturningDocNo.get());
        txTemplate.executeWithoutResult(s -> collectionReceiptAppService.approve(docNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> collectionReceiptAppService.post(docNo, OPERATOR));
        return docNo;
    }

    private String createApprovePaymentAndPost(java.util.function.Supplier<String> createReturningDocNo) {
        String docNo = txTemplate.execute(s -> createReturningDocNo.get());
        txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.approve(docNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.post(docNo, OPERATOR));
        return docNo;
    }

    private long createPaymentAccount(String name) {
        PaymentAccount account = txTemplate.execute(s -> paymentAccountService.create(
                new PaymentAccountCommand(null, name, PaymentAccountType.BANK, GL_BANK,
                        "测试开户行", "6222" + Long.toString(System.nanoTime(), 36)), OPERATOR));
        assertThat(account).isNotNull();
        assertThat(account.getId()).isNotNull();
        return account.getId();
    }

    private void ensurePeriodOpen(String period) {
        if (!accountingPeriodService.isOpen(period)) {
            try {
                txTemplate.executeWithoutResult(s -> accountingPeriodService.open(period, OPERATOR));
            } catch (RuntimeException ignore) {
                // 并发/已存在：忽略（共享 MySQL，他测可能先开当月账期）
            }
        }
    }

    // ---------------------------------------------------------------
    // 断言工具
    // ---------------------------------------------------------------

    private String docStatus(String table, String docNo) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE doc_no = ?",
                String.class, docNo);
    }

    private String receivableStatus(String invoiceNo) {
        return jdbc.queryForObject("SELECT status FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", String.class, invoiceNo);
    }

    private BigDecimal settlementSum(String type, long targetId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM settlement_record "
                + "WHERE tenant_id = 0 AND settlement_type = ? AND target_id = ?",
                BigDecimal.class, type, targetId);
    }

    private Long settlementRecordCount(String type, long targetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record "
                + "WHERE tenant_id = 0 AND settlement_type = ? AND target_id = ?",
                Long.class, type, targetId);
    }

    private Long negativeRecordCount(String type, long targetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record "
                + "WHERE tenant_id = 0 AND settlement_type = ? AND target_id = ? AND amount < 0",
                Long.class, type, targetId);
    }

    /** 取某来源单据的某类型自动凭证（非红字）的状态。 */
    private String autoVoucherStatus(String sourceDocNo, String autoType) {
        return jdbc.queryForObject("SELECT status FROM voucher WHERE tenant_id = 0 "
                + "AND source_doc_no = ? AND source_doc_type = ?", String.class, sourceDocNo, autoType);
    }

    /** 以某来源单据的某类型自动凭证为来源的红字（VOUCHER_REVERSAL）凭证条数。 */
    private Long reversalVoucherCount(String sourceDocNo, String autoType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM voucher WHERE tenant_id = 0 "
                + "AND source_doc_type = 'VOUCHER_REVERSAL' AND source_doc_no IN "
                + "(SELECT doc_no FROM voucher WHERE tenant_id = 0 AND source_doc_no = ? "
                + "AND source_doc_type = ?)", Long.class, sourceDocNo, autoType);
    }

    /** 某科目在「现金侧自动凭证 + 其红字凭证」上借−贷净额归零（原 REVERSED + 红字 APPROVED 都计入）。 */
    private void assertCashSourceNetZero(String accountCode, String sourceDocNo) {
        String autoVoucherNo = jdbc.queryForObject("SELECT doc_no FROM voucher WHERE tenant_id = 0 "
                + "AND source_doc_no = ? AND source_doc_type <> 'VOUCHER_REVERSAL' LIMIT 1",
                String.class, sourceDocNo);
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(vl.debit - vl.credit), 0) FROM voucher_line vl "
                        + "JOIN voucher v ON v.id = vl.voucher_id "
                        + "WHERE v.tenant_id = 0 AND vl.account_code = ? "
                        + "AND (v.source_doc_no = ? OR v.source_doc_no = ?)",
                BigDecimal.class, accountCode, sourceDocNo, autoVoucherNo);
        assertThat(net).as("科目 %s 跨原+红字现金侧凭证净额归零", accountCode).isEqualByComparingTo("0");
    }

    private long auditCount(String action) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?",
                Long.class, action);
        return c == null ? 0L : c;
    }

    private boolean receivableInAging(long customerId) {
        var report = agingReportDao.receivableAging(LocalDate.now(ZoneOffset.UTC), customerId, 1,
                AgingReportDao.MAX_SIZE);
        return !report.page().items().isEmpty();
    }

    private boolean payableInAging(long supplierId) {
        var report = agingReportDao.payableAging(LocalDate.now(ZoneOffset.UTC), supplierId, 1,
                AgingReportDao.MAX_SIZE);
        return !report.page().items().isEmpty();
    }

    private void assertSubledgerReceivable(long arId, String amount, String settled, String status) {
        var row = jdbc.queryForMap("SELECT amount, settled_amount, status FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND id = ?", arId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(amount);
        assertThat((BigDecimal) row.get("settled_amount")).isEqualByComparingTo(settled);
        assertThat(row.get("status")).isEqualTo(status);
    }

    private void assertSubledgerPayable(long apId, String amount, String settled, String status) {
        var row = jdbc.queryForMap("SELECT amount, settled_amount, status FROM accounts_payable "
                + "WHERE tenant_id = 0 AND id = ?", apId);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(amount);
        assertThat((BigDecimal) row.get("settled_amount")).isEqualByComparingTo(settled);
        assertThat(row.get("status")).isEqualTo(status);
    }

    // ---------------------------------------------------------------
    // 夹具：经真实发票过账链路生成应收/应付（沿用 CollectionPaymentFlowIntegrationTest 驱动方式，
    // 业务日参数化以落指定账期）
    // ---------------------------------------------------------------

    private long createPayableViaInvoicePosting(long supplierId, long warehouseId, long productId,
                                                String suffix, String pinvNo, LocalDate d) {
        String poNo = "PO-SRP-" + suffix;
        String prNo = "PR-SRP-" + suffix;

        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "夹具采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d, "收货",
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForPurchaseReceipt(purchaseReceiptService.post(prNo, OPERATOR), OPERATOR));

        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-SRP", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForPurchaseInvoice(
                        purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR), OPERATOR));

        Long apId = jdbc.queryForObject("SELECT id FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, pinvNo);
        assertThat(apId).isNotNull();
        return apId;
    }

    private long createReceivableViaInvoicePosting(long supplierId, long warehouseId, long productId,
                                                  long customerId, String suffix, String sinvNo,
                                                  LocalDate d) {
        String poNo = "PO-SRR-" + suffix;
        String prNo = "PR-SRR-" + suffix;
        String pinvNo = "PINV-SRR-" + suffix;
        String soNo = "SO-SRR-" + suffix;
        String sdNo = "SD-SRR-" + suffix;

        // 备货：采购 100@12.50 入库过账（库存 100/1250.00）+ 自动凭证
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "备货采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d, "收货",
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForPurchaseReceipt(purchaseReceiptService.post(prNo, OPERATOR), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-SRR", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForPurchaseInvoice(
                        purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR), OPERATOR));

        // 销售：60@20 下单 → 出库过账（COGS=750）+ 自动凭证 → 60@25 发票过账（应收 1500）+ 自动凭证
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, d, "夹具销售",
                List.of(new SalesOrderLineInput(productId, new BigDecimal("60"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(soNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(sdNo, soNo, warehouseId, "发货",
                List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("60"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(sdNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForSalesDelivery(salesDeliveryService.post(sdNo, OPERATOR), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(sinvNo, sdNo, customerId, d,
                d.plusMonths(1), "开票",
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("60"),
                        new BigDecimal("25"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(sinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForSalesInvoice(salesInvoiceService.post(sinvNo, OPERATOR), OPERATOR));

        Long arId = jdbc.queryForObject("SELECT id FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, sinvNo);
        assertThat(arId).isNotNull();
        return arId;
    }
}
