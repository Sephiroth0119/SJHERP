package com.sjherp.app.config;

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

import com.sjherp.app.collection.CollectionDtos.CollectionReceiptLineRequest;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.payment.PaymentDtos.PaymentDisbursementLineRequest;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountCommand;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.fund.PaymentAccountType;
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
 * 收/付款单全链路真库集成测试（M4-T04b 验收核心，Testcontainers 真实 MySQL，设计真源 §2.3/§3）。
 *
 * <p>用生产同套装配跑通收/付款单的<b>过账编排</b>——这是 T04b 的核心契约：过账时在<b>同一
 * {@code @Transactional}</b> 内（a）推进单据状态机至 COMPLETED、（b）逐行经核销引擎冲减应收/应付
 * 子账、（c）生成现金侧凭证。三者原子同事务，任一失败整单回滚。
 *
 * <p><b>装配</b>：@Import 真实 {@link AuditConfig}（DomainEventPublisher + @Audited 切面）+
 * {@link InventoryInfraConfig} + {@link PurchaseInfraConfig}（应付仓储/采购链路）+
 * {@link SalesInfraConfig}（应收仓储/ReceivableService/销售链路）+ {@link GlInfraConfig}（科目/账期/
 * 凭证/AutoVoucherService）+ {@link SettlementInfraConfig}（SettlementService）+ {@link FundInfraConfig}
 * （资金账户档案，依赖 GL AccountRepository 校验 glAccountCode 末级启用）+ {@link CollectionInfraConfig}
 * + {@link PaymentInfraConfig}。两个 AppService 是 @Service 组件，本上下文无组件扫描，故在 TestConfig
 * 显式 @Bean 装配（按类型注入既有协作 Bean）。{@link DocumentNumberGenerator} 同 BusinessToVoucher
 * 范本在 TestConfig 自备（catalog 整套档案 Bean 不引入）。
 *
 * <p><b>驱动方式</b>（沿用 {@link SettlementEngineIntegrationTest} / {@link PurchaseToSalesFlowIntegrationTest}）：
 * 经领域服务直驱采购/销售链路生成真实 AP/AR，自造 supplier/customer/warehouse/product id 隔离；
 * 收/付款单的建/审/过账经 <b>AppService</b>（验证真实编排，而非测试自拼）。
 *
 * <p><b>四组验收</b>（任务书）：
 * <ol>
 *   <li>付款：采购→入库→发票→AP（1250）→建资金账户(1002)→付款单(部分 500 + 补齐 750 两单)→approve→post，
 *       断言 AP settled_amount/status 落库、settlement_record paymentDocNo=PAYV 单号、凭证借 220202/贷 1002
 *       借贷平衡、试算平衡、幂等（重 post 被状态机拒）；</li>
 *   <li>收款：销售→出库→发票→AR（1500）→收款单(1002 全额)→post，断言 AR 核销 SETTLED + 借 1002/贷 1122 凭证；</li>
 *   <li>跨对手方：收款单含他客户的 AR → post 抛 IllegalArgumentException 整单回滚（子账/凭证/单据状态不变）；</li>
 *   <li>超额分摊：付款单分摊额 &gt; AP 未核销余额 → OverSettlementException 回滚。</li>
 * </ol>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class CollectionPaymentFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-collpay";

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
    private static PaymentDisbursementAppService paymentDisbursementAppService;
    private static AutoVoucherService autoVoucherService;
    private static VoucherService voucherService;

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
        paymentDisbursementAppService = context.getBean(PaymentDisbursementAppService.class);
        autoVoucherService = context.getBean(AutoVoucherService.class);
        voucherService = context.getBean(VoucherService.class);
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
            SalesInfraConfig.class, GlInfraConfig.class, SettlementInfraConfig.class,
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

        // 编号生成器：AutoVoucherService（VCH-）/ PaymentAccountService（FA-）/ 两 AppService（RCPT-/PAYV-）
        // 共用。生产由 CatalogInfraConfig 注册，此处显式 new 一份（不引入 catalog 整套档案 Bean 闭包，
        // 范本同 BusinessToVoucherFlowIntegrationTest）。
        @Bean
        SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
            return new JdbcSequenceProvider(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
            return new DefaultDocumentNumberGenerator(sequenceProvider);
        }

        // VoucherAppService 生产是 @Service 组件，本上下文无组件扫描，显式装配（M4-T07c 收付款单红冲复用其
        // 凭证红冲基元 reverse）。
        @Bean
        VoucherAppService voucherAppService(VoucherService voucherService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new VoucherAppService(voucherService, documentNumberGenerator);
        }

        // 两个 AppService 生产是 @Service 组件，本上下文无组件扫描，显式装配（按类型注入既有协作 Bean）。
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
        PaymentDisbursementAppService paymentDisbursementAppService(
                com.sjherp.domain.payment.PaymentDisbursementService paymentDisbursementService,
                PaymentAccountService paymentAccountService,
                com.sjherp.domain.payable.AccountsPayableRepository payableRepository,
                com.sjherp.domain.settlement.SettlementService settlementService,
                com.sjherp.domain.settlement.SettlementRecordRepository settlementRecordRepository,
                AutoVoucherService autoVoucherService,
                VoucherService voucherService,
                VoucherAppService voucherAppService,
                DocumentNumberGenerator documentNumberGenerator) {
            return new PaymentDisbursementAppService(paymentDisbursementService, paymentAccountService,
                    payableRepository, settlementService, settlementRecordRepository, autoVoucherService,
                    voucherService, voucherAppService, documentNumberGenerator);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // =================================================================================
    // 验收①-付款：采购→AP(1250) → 资金账户(1002) → 付款单(部分 500 + 补齐 750 两单) → 过账
    //   断言：AP settled_amount/status 落库、settlement_record paymentDocNo=PAYV 单号、
    //   凭证借 220202/贷 1002 借贷平衡、试算平衡、幂等（重 post 被状态机拒）。
    // =================================================================================

    @Test
    void 付款单过账_核销应付_现金侧凭证借应付贷银行_幂等被状态机拒() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String pinvNo = "PINV-CP-" + suffix;

        // 经采购整链过账真实生成应付（采购 100@12.50 → 应付 1250.00）。
        long apId = createPayableViaInvoicePosting(supplierId, warehouseId, productId, suffix, pinvNo);
        assertSubledgerPayable(apId, "1250.00", "0.00", "OPEN");

        long acctId = createPaymentAccount("付款用银行账户-" + suffix);
        LocalDate payDate = LocalDate.of(2026, 6, 14);

        // ---- 第一单：部分付款 500.00 → AP PARTIAL ----
        String payNo1 = createApprovePost(() ->
                paymentDisbursementAppService.create(supplierId, acctId, payDate, "部分付款",
                        List.of(new PaymentDisbursementLineRequest(apId, new BigDecimal("500.00"))),
                        OPERATOR).getDocNo());
        assertSubledgerPayable(apId, "1250.00", "500.00", "PARTIAL");
        assertThat(payNo1).as("付款单号 PAYV- 前缀").startsWith("PAYV-");

        // ---- 第二单：补齐 750.00 → AP SETTLED ----
        String payNo2 = createApprovePost(() ->
                paymentDisbursementAppService.create(supplierId, acctId, payDate, "补齐付款",
                        List.of(new PaymentDisbursementLineRequest(apId, new BigDecimal("750.00"))),
                        OPERATOR).getDocNo());
        assertSubledgerPayable(apId, "1250.00", "1250.00", "SETTLED");

        // ---- 核销记录：两笔均回填 paymentDocNo=对应 PAYV 单号、targetId=apId、合计=已核销额 ----
        Long recCount = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record WHERE tenant_id = 0 "
                + "AND settlement_type = 'PAYABLE' AND target_id = ?", Long.class, apId);
        assertThat(recCount).as("应付核销记录两笔").isEqualTo(2L);
        BigDecimal recSum = jdbc.queryForObject("SELECT SUM(amount) FROM settlement_record WHERE tenant_id = 0 "
                + "AND settlement_type = 'PAYABLE' AND target_id = ?", BigDecimal.class, apId);
        assertThat(recSum).as("Σ核销记录 = 已核销额").isEqualByComparingTo("1250.00");
        assertThat(paymentDocNoOf("PAYABLE", apId, "500.00"))
                .as("第一笔核销记录回填第一单 PAYV 单号").isEqualTo(payNo1);
        assertThat(paymentDocNoOf("PAYABLE", apId, "750.00"))
                .as("第二笔核销记录回填第二单 PAYV 单号").isEqualTo(payNo2);

        // ---- 现金侧凭证：每单恰一组（借 220202 应付、贷 1002 银行，借贷平衡，金额=付款额） ----
        assertCashVoucher(payNo1, "PAYMENT_DISBURSEMENT", "220202", "1002", "500.00");
        assertCashVoucher(payNo2, "PAYMENT_DISBURSEMENT", "220202", "1002", "750.00");

        // ---- 试算平衡（付款日所属账期 Σ借==Σ贷） ----
        assertTrialBalanced("202606");

        // ---- 幂等：已 COMPLETED 的付款单重 post → 状态机拒（IllegalStateTransitionException）；DB 态不变 ----
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.post(payNo1, OPERATOR)))
                .as("重过账被单据状态机拒").isInstanceOf(IllegalStateTransitionException.class);
        // 重 post 失败后：AP 仍 SETTLED、核销记录仍两笔、凭证仍每单一组（无重复核销/重复凭证）
        assertSubledgerPayable(apId, "1250.00", "1250.00", "SETTLED");
        Long recCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record WHERE tenant_id = 0 "
                + "AND settlement_type = 'PAYABLE' AND target_id = ?", Long.class, apId);
        assertThat(recCountAfter).as("重过账被拒后核销记录数不变").isEqualTo(2L);
        assertThat(voucherService.findBySourceDocNo(payNo1)).as("付款凭证幂等仍 1 张").hasSize(1);
    }

    // =================================================================================
    // 验收②-收款：销售→AR(1500) → 收款单(1002 全额) → 过账
    //   断言：AR 核销 SETTLED、凭证借 1002/贷 1122、试算平衡。
    // =================================================================================

    @Test
    void 收款单过账_全额核销应收_现金侧凭证借银行贷应收() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String sinvNo = "SINV-CP-" + suffix;

        // 经销售整链过账真实生成应收（销售 60@25 → 应收 1500.00）。
        long arId = createReceivableViaInvoicePosting(supplierId, warehouseId, productId, customerId,
                suffix, sinvNo);
        assertSubledgerReceivable(arId, "1500.00", "0.00", "OPEN");

        long acctId = createPaymentAccount("收款用银行账户-" + suffix);
        LocalDate receiptDate = LocalDate.of(2026, 6, 14);

        String rcptNo = createApproveCollectionAndPost(() ->
                collectionReceiptAppService.create(customerId, acctId, receiptDate, "全额收款",
                        List.of(new CollectionReceiptLineRequest(arId, new BigDecimal("1500.00"))),
                        OPERATOR).getDocNo());
        assertThat(rcptNo).as("收款单号 RCPT- 前缀").startsWith("RCPT-");

        // ---- AR 全额核销 SETTLED ----
        assertSubledgerReceivable(arId, "1500.00", "1500.00", "SETTLED");

        // ---- 核销记录回填 RCPT 单号 ----
        BigDecimal recSum = jdbc.queryForObject("SELECT SUM(amount) FROM settlement_record WHERE tenant_id = 0 "
                + "AND settlement_type = 'RECEIVABLE' AND target_id = ?", BigDecimal.class, arId);
        assertThat(recSum).as("Σ核销记录 = 已核销额").isEqualByComparingTo("1500.00");
        assertThat(paymentDocNoOf("RECEIVABLE", arId, "1500.00"))
                .as("核销记录回填 RCPT 单号").isEqualTo(rcptNo);

        // ---- 现金侧凭证：借 1002 银行、贷 1122 应收，借贷平衡 ----
        assertCashVoucher(rcptNo, "COLLECTION_RECEIPT", "1002", "1122", "1500.00");

        // ---- 试算平衡 ----
        assertTrialBalanced("202606");
    }

    // =================================================================================
    // 验收③-跨对手方：收款单分摊到他客户的 AR → post 抛 IllegalArgumentException 整单回滚。
    //   子账（已核销/状态）、凭证、单据状态均不变（原子回滚）。
    // =================================================================================

    @Test
    void 跨客户核销_收款单引用他客户应收_post抛IAE整单回滚() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        long otherCustomerId = nextId();   // 收款单挂在他客户名下
        String suffix = Long.toString(System.nanoTime(), 36);
        String sinvNo = "SINV-XC-" + suffix;

        long arId = createReceivableViaInvoicePosting(supplierId, warehouseId, productId, customerId,
                suffix, sinvNo);
        assertSubledgerReceivable(arId, "1500.00", "0.00", "OPEN");

        long acctId = createPaymentAccount("跨客户银行账户-" + suffix);
        LocalDate receiptDate = LocalDate.of(2026, 6, 14);

        // 建单：收款单客户 = otherCustomerId，但分摊行引用 customerId 的应收 → post 时对手方校验失败。
        String rcptNo = txTemplate.execute(s ->
                collectionReceiptAppService.create(otherCustomerId, acctId, receiptDate, "跨客户错误收款",
                        List.of(new CollectionReceiptLineRequest(arId, new BigDecimal("1500.00"))),
                        OPERATOR).getDocNo());
        txTemplate.executeWithoutResult(s -> collectionReceiptAppService.approve(rcptNo, OPERATOR));

        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> collectionReceiptAppService.post(rcptNo, OPERATOR)))
                .as("跨客户核销被 AppService 拒").isInstanceOf(IllegalArgumentException.class);

        // 整单回滚：AR 未核销（仍 OPEN/0.00）、无核销记录、无现金侧凭证；单据状态回滚到 post 前（仍 APPROVED）。
        assertSubledgerReceivable(arId, "1500.00", "0.00", "OPEN");
        Long recCount = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record WHERE tenant_id = 0 "
                + "AND settlement_type = 'RECEIVABLE' AND target_id = ?", Long.class, arId);
        assertThat(recCount).as("跨客户回滚后无核销记录").isZero();
        assertThat(voucherService.findBySourceDocNo(rcptNo)).as("跨客户回滚后无现金侧凭证").isEmpty();
        String status = jdbc.queryForObject("SELECT status FROM collection_receipt WHERE tenant_id = 0 "
                + "AND doc_no = ?", String.class, rcptNo);
        assertThat(status).as("post 回滚后收款单状态保持 APPROVED（COMPLETED 未持久化）").isEqualTo("APPROVED");
    }

    // =================================================================================
    // 验收④-超额：付款单分摊额 > AP 未核销余额 → OverSettlementException 回滚。
    //   子账、凭证、单据状态均不变（原子回滚）。
    // =================================================================================

    @Test
    void 超额核销_付款单分摊超应付余额_post抛OverSettlement整单回滚() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String pinvNo = "PINV-OV-" + suffix;

        long apId = createPayableViaInvoicePosting(supplierId, warehouseId, productId, suffix, pinvNo);
        assertSubledgerPayable(apId, "1250.00", "0.00", "OPEN");

        long acctId = createPaymentAccount("超额银行账户-" + suffix);
        LocalDate payDate = LocalDate.of(2026, 6, 14);

        // 分摊 1250.01 > AP 1250.00 → 核销引擎抛 OverSettlementException。
        String payNo = txTemplate.execute(s ->
                paymentDisbursementAppService.create(supplierId, acctId, payDate, "超额付款",
                        List.of(new PaymentDisbursementLineRequest(apId, new BigDecimal("1250.01"))),
                        OPERATOR).getDocNo());
        txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.approve(payNo, OPERATOR));

        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.post(payNo, OPERATOR)))
                .as("超额付款被核销引擎拒").isInstanceOf(OverSettlementException.class);

        // 整单回滚：AP 未核销（仍 OPEN/0.00）、无核销记录、无现金侧凭证；单据状态保持 APPROVED。
        assertSubledgerPayable(apId, "1250.00", "0.00", "OPEN");
        Long recCount = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_record WHERE tenant_id = 0 "
                + "AND settlement_type = 'PAYABLE' AND target_id = ?", Long.class, apId);
        assertThat(recCount).as("超额回滚后无核销记录").isZero();
        assertThat(voucherService.findBySourceDocNo(payNo)).as("超额回滚后无现金侧凭证").isEmpty();
        String status = jdbc.queryForObject("SELECT status FROM payment_disbursement WHERE tenant_id = 0 "
                + "AND doc_no = ?", String.class, payNo);
        assertThat(status).as("post 回滚后付款单状态保持 APPROVED").isEqualTo("APPROVED");
    }

    // ---------------------------------------------------------------
    // 收/付款单 建→审→过账 编排（各步独立外层事务，模拟 REST 调用边界）
    // ---------------------------------------------------------------

    /** 付款单：建（取单号）→审→过账，每步独立外层事务（@Transactional AppService 方法）。返回单号。 */
    private String createApprovePost(java.util.function.Supplier<String> createReturningDocNo) {
        String docNo = txTemplate.execute(s -> createReturningDocNo.get());
        txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.approve(docNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> paymentDisbursementAppService.post(docNo, OPERATOR));
        return docNo;
    }

    /** 收款单：建→审→过账，每步独立外层事务。返回单号。 */
    private String createApproveCollectionAndPost(java.util.function.Supplier<String> createReturningDocNo) {
        String docNo = txTemplate.execute(s -> createReturningDocNo.get());
        txTemplate.executeWithoutResult(s -> collectionReceiptAppService.approve(docNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> collectionReceiptAppService.post(docNo, OPERATOR));
        return docNo;
    }

    /** 建资金账户（glAccountCode=1002 银行存款，FA- 自动编号），返回回填的 id。 */
    private long createPaymentAccount(String name) {
        PaymentAccount account = txTemplate.execute(s -> paymentAccountService.create(
                new PaymentAccountCommand(null, name, PaymentAccountType.BANK, GL_BANK,
                        "测试开户行", "6222" + Long.toString(System.nanoTime(), 36)), OPERATOR));
        assertThat(account).isNotNull();
        assertThat(account.getId()).as("资金账户落库回填 id").isNotNull();
        assertThat(account.getGlAccountCode()).isEqualTo(GL_BANK);
        return account.getId();
    }

    // ---------------------------------------------------------------
    // 断言工具
    // ---------------------------------------------------------------

    /**
     * 现金侧凭证断言：来源单号 sourceDocNo 恰一组凭证、status=APPROVED、source 两列正确、
     * 借方科目 debitAcc 金额=amount、贷方科目 creditAcc 金额=amount、Σ借==Σ贷。
     */
    private void assertCashVoucher(String sourceDocNo, String expectedType, String debitAcc,
                                   String creditAcc, String amount) {
        List<com.sjherp.domain.gl.Voucher> vouchers = voucherService.findBySourceDocNo(sourceDocNo);
        assertThat(vouchers).as("来源单据 %s 恰一组现金侧凭证", sourceDocNo).hasSize(1);
        com.sjherp.domain.gl.Voucher v = vouchers.get(0);
        assertThat(v.getStatus().name()).as("凭证 %s 状态 APPROVED", v.getDocNo()).isEqualTo("APPROVED");
        assertThat(v.getSourceDocType()).as("source_doc_type 回填").isEqualTo(expectedType);
        assertThat(v.getSourceDocNo()).as("source_doc_no 回填").isEqualTo(sourceDocNo);
        BigDecimal debit = lineDebit(v, debitAcc);
        BigDecimal credit = lineCredit(v, creditAcc);
        assertThat(debit).as("凭证 %s 借 %s = 金额", v.getDocNo(), debitAcc).isEqualByComparingTo(amount);
        assertThat(credit).as("凭证 %s 贷 %s = 金额", v.getDocNo(), creditAcc).isEqualByComparingTo(amount);
        // 整凭证 Σ借==Σ贷（不限定到两科目，纵深防御额外行）
        BigDecimal totalDebit = v.getLines().stream().map(l -> l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = v.getLines().stream().map(l -> l.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).as("凭证 %s Σ借=Σ贷", v.getDocNo()).isEqualByComparingTo(totalCredit);
    }

    private BigDecimal lineDebit(com.sjherp.domain.gl.Voucher voucher, String accountCode) {
        return voucher.getLines().stream()
                .filter(l -> l.getAccountCode().equals(accountCode))
                .map(l -> l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal lineCredit(com.sjherp.domain.gl.Voucher voucher, String accountCode) {
        return voucher.getLines().stream()
                .filter(l -> l.getAccountCode().equals(accountCode))
                .map(l -> l.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void assertTrialBalanced(String period) {
        var balances = voucherService.trialBalance(period);
        BigDecimal totalDebit = balances.stream()
                .map(b -> b.totalDebit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = balances.stream()
                .map(b -> b.totalCredit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).as("账期 %s 试算平衡 Σ借 = Σ贷", period).isEqualByComparingTo(totalCredit);
    }

    /** 取某子账某金额核销记录的 payment_doc_no（按 amount 唯一定位本测试用样本）。 */
    private String paymentDocNoOf(String type, long targetId, String amount) {
        return jdbc.queryForObject("SELECT payment_doc_no FROM settlement_record WHERE tenant_id = 0 "
                        + "AND settlement_type = ? AND target_id = ? AND amount = ?",
                String.class, type, targetId, new BigDecimal(amount));
    }

    private void assertSubledgerReceivable(long arId, String amount, String settled, String status) {
        var row = jdbc.queryForMap("SELECT amount, settled_amount, status FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND id = ?", arId);
        assertThat((BigDecimal) row.get("amount")).as("应收 %d amount", arId).isEqualByComparingTo(amount);
        assertThat((BigDecimal) row.get("settled_amount")).as("应收 %d settled_amount 落库", arId)
                .isEqualByComparingTo(settled);
        assertThat(row.get("status")).as("应收 %d status 落库", arId).isEqualTo(status);
    }

    private void assertSubledgerPayable(long apId, String amount, String settled, String status) {
        var row = jdbc.queryForMap("SELECT amount, settled_amount, status FROM accounts_payable "
                + "WHERE tenant_id = 0 AND id = ?", apId);
        assertThat((BigDecimal) row.get("amount")).as("应付 %d amount", apId).isEqualByComparingTo(amount);
        assertThat((BigDecimal) row.get("settled_amount")).as("应付 %d settled_amount 落库", apId)
                .isEqualByComparingTo(settled);
        assertThat(row.get("status")).as("应付 %d status 落库", apId).isEqualTo(status);
    }

    // ---------------------------------------------------------------
    // 夹具：经真实发票过账链路生成应收/应付（沿用 SettlementEngineIntegrationTest 驱动方式，
    // 含 T02 自动凭证手动同事务调用——使应付/应收凭证与本测试现金侧凭证落同一账期可试算平衡）
    // ---------------------------------------------------------------

    /** 采购 100@12.50 整链过账后返回生成的应付主键（应付 1250.00，来源 = 采购发票号）。 */
    private long createPayableViaInvoicePosting(long supplierId, long warehouseId, long productId,
                                                String suffix, String pinvNo) {
        String poNo = "PO-CP-" + suffix;
        String prNo = "PR-CP-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

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
                SettlementMethod.MONTHLY, d, "INV-CP", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForPurchaseInvoice(
                        purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR), OPERATOR));

        Long apId = jdbc.queryForObject("SELECT id FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, pinvNo);
        assertThat(apId).as("采购发票过账应生成应付").isNotNull();
        return apId;
    }

    /** 采购 100@12.50 备货 + 销售 60@25 整链过账后返回生成的应收主键（应收 1500.00，来源 = 销售发票号）。 */
    private long createReceivableViaInvoicePosting(long supplierId, long warehouseId, long productId,
                                                  long customerId, String suffix, String sinvNo) {
        String poNo = "PO-CP-R-" + suffix;
        String prNo = "PR-CP-R-" + suffix;
        String pinvNo = "PINV-CP-R-" + suffix;
        String soNo = "SO-CP-R-" + suffix;
        String sdNo = "SD-CP-R-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

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
                SettlementMethod.MONTHLY, d, "INV-CP-R", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                autoVoucherService.generateForPurchaseInvoice(
                        purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR), OPERATOR));

        // 销售：60@20 下单 → 出库过账（COGS=60×12.50=750.00）+ 自动凭证 → 60@25 发票过账（应收 1500.00）+ 自动凭证
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
        assertThat(arId).as("销售发票过账应生成应收").isNotNull();
        return arId;
    }
}
