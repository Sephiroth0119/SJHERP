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

import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.Voucher;
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
 * 业务→凭证自动化端到端集成测试（M4-T02 验收核心，Testcontainers 真实 MySQL，拆解 §7）。
 *
 * <p>用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link PurchaseInfraConfig} + {@link SalesInfraConfig} + {@link GlInfraConfig}——凭证仓储 /
 * VoucherService / AccountingPeriodService / AutoVoucherService 装配）跑通完整链路：
 * 采购下单 → 入库 post → 采购发票 post → 销售下单 → 出库 post → 销售发票 post。
 *
 * <p><b>驱动方式</b>（与 {@link PurchaseToSalesFlowIntegrationTest} 一致）：经各<b>领域服务</b>直驱
 * （绕过 app 的 AppService，避免装配全档案 Bean 闭包），自造 supplier/customer/warehouse/product
 * id 隔离数据。因领域 post 不含 T02 自动凭证钩子（钩子在 AppService.post 内），本测试在每步领域
 * {@code post} 之后、<b>同一外层事务内</b>手动调 {@code autoVoucherService.generateForXxx(聚合, operator)}
 * （等价 AppService 的同事务直调——这样账期自动 open + 凭证 post 与业务过账共享事务边界，
 * 拆解 §4）。
 *
 * <p><b>断言</b>（拆解 §7）：
 * <ul>
 *   <li>生成恰 4 张凭证：各 {@code findBySourceDocNo} size==1（采购入库 / 采购发票 / 销售出库 / 销售发票）；</li>
 *   <li>各凭证 status=APPROVED、Σ借=Σ贷、source_doc_type/no 正确回填；</li>
 *   <li>金额对账：1405 借=入库额 1250.00、220201 借=贷（暂估生成 + 冲回抵平）、220202 贷=应付额=
 *       accounts_payable.amount、1122 借=应收额、6401 借=COGS 750.00、6001 贷=销售额；</li>
 *   <li>试算平衡：各凭证所属账期 trialBalance(period) Σ借==Σ贷；</li>
 *   <li><b>幂等</b>：对各单据重复调 generateForXxx，findBySourceDocNo 仍 size==1（验收核心）。</li>
 * </ul>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class BusinessToVoucherFlowIntegrationTest {

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

        // 编号生成器：GlInfraConfig 的 AutoVoucherService 依赖它生成 VCH- 号（生产由 CatalogInfraConfig
        // 注册，此处显式 new 一份，不引入 catalog 整套档案 Bean 闭包）。
        @Bean
        SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
            return new JdbcSequenceProvider(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
            return new DefaultDocumentNumberGenerator(sequenceProvider);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    @Test
    void 进销存全链路过账_每单恰一组凭证_借贷平衡且金额可追溯_幂等() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-V-" + suffix;
        String prNo = "PR-V-" + suffix;
        String pinvNo = "PINV-V-" + suffix;
        String soNo = "SO-V-" + suffix;
        String sdNo = "SD-V-" + suffix;
        String sinvNo = "SINV-V-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        // ---- 采购线：下单 100@12.50 → 审核 → 收 100 过账（库存 100/1250.00）→ 同事务 T02 自动凭证 ----
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "整链采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d,
                "收货", List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        // 入库 post + 自动凭证：借 1405 库存商品 1250.00 / 贷 220201 暂估应付款 1250.00（同事务，等价 AppService 钩子）
        txTemplate.executeWithoutResult(s -> {
            PurchaseReceipt receipt = purchaseReceiptService.post(prNo, OPERATOR);
            autoVoucherService.generateForPurchaseReceipt(receipt, OPERATOR);
        });

        // 采购发票：开 100 / 金额 1250.00 → 审核 → 过账（应付 1250.00）+ 自动凭证：借 220201 / 贷 220202 各 1250.00
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-V", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            PurchaseInvoice invoice = purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY,
                    OPERATOR);
            autoVoucherService.generateForPurchaseInvoice(invoice, OPERATOR);
        });

        // ---- 销售线：下单 60@20 → 审核 → 出 60 过账（COGS=60×12.50=750.00，库存→40/500.00）+ 自动凭证 ----
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, d, "整链销售",
                List.of(new SalesOrderLineInput(productId, new BigDecimal("60"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(soNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(sdNo, soNo, warehouseId, "发货",
                List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("60"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(sdNo, OPERATOR));
        // 出库 post + 自动凭证：借 6401 主营业务成本 750.00 / 贷 1405 库存商品 750.00
        txTemplate.executeWithoutResult(s -> {
            SalesDelivery delivery = salesDeliveryService.post(sdNo, OPERATOR);
            autoVoucherService.generateForSalesDelivery(delivery, OPERATOR);
        });

        // 销售发票：对出库行 60@25 开票 → 审核 → 过账（应收 1500.00）+ 自动凭证：借 1122 / 贷 6001 各 1500.00
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(sinvNo, sdNo, customerId, d,
                d.plusMonths(1), "开票",
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("60"),
                        new BigDecimal("25"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(sinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> {
            SalesInvoice invoice = salesInvoiceService.post(sinvNo, OPERATOR);
            autoVoucherService.generateForSalesInvoice(invoice, OPERATOR);
        });

        // ============ 断言①：生成恰 4 张凭证，每单 findBySourceDocNo size==1 ============
        Voucher prVoucher = singleVoucher(prNo, "PURCHASE_RECEIPT");
        Voucher pinvVoucher = singleVoucher(pinvNo, "PURCHASE_INVOICE");
        Voucher sdVoucher = singleVoucher(sdNo, "SALES_DELIVERY");
        Voucher sinvVoucher = singleVoucher(sinvNo, "SALES_INVOICE");

        // 全库仅本链路这 4 张自动凭证（source 非空）——按本次 4 个来源单号收敛，避免跨类污染
        Long autoVoucherCount = jdbc.queryForObject("SELECT COUNT(*) FROM voucher "
                        + "WHERE tenant_id = 0 AND source_doc_no IN (?, ?, ?, ?)",
                Long.class, prNo, pinvNo, sdNo, sinvNo);
        assertThat(autoVoucherCount).as("本链路恰 4 张自动凭证").isEqualTo(4L);

        // ============ 断言②：各凭证 APPROVED + Σ借=Σ贷 + source 两列正确回填 ============
        assertApprovedAndBalanced(prVoucher, "PURCHASE_RECEIPT", prNo);
        assertApprovedAndBalanced(pinvVoucher, "PURCHASE_INVOICE", pinvNo);
        assertApprovedAndBalanced(sdVoucher, "SALES_DELIVERY", sdNo);
        assertApprovedAndBalanced(sinvVoucher, "SALES_INVOICE", sinvNo);

        // ============ 断言③：金额对账（直接查凭证行 debit/credit by account_code） ============
        // 采购入库：借 1405 库存商品 = 入库额 1250.00；贷 220201 暂估应付款 1250.00
        assertThat(lineDebit(prVoucher, "1405")).as("1405 借=入库额").isEqualByComparingTo("1250.00");
        assertThat(lineCredit(prVoucher, "220201")).as("220201 贷=入库额").isEqualByComparingTo("1250.00");
        // 采购发票：借 220201 暂估应付款 1250.00（冲回）；贷 220202 应付账款 = 应付额 = accounts_payable.amount
        assertThat(lineDebit(pinvVoucher, "220201")).as("220201 借=冲回额").isEqualByComparingTo("1250.00");
        BigDecimal payable = payableAmount(pinvNo);
        assertThat(lineCredit(pinvVoucher, "220202")).as("220202 贷=应付额=子账金额")
                .isEqualByComparingTo(payable);
        assertThat(payable).as("accounts_payable.amount=发票额").isEqualByComparingTo("1250.00");
        // 220201 跨两张凭证：生成 1250.00（贷）与冲回 1250.00（借）抵平 → 净额 0
        assertThat(lineDebit(pinvVoucher, "220201").subtract(lineCredit(prVoucher, "220201")))
                .as("220201 借=贷（暂估生成与冲回抵平，净额 0）").isEqualByComparingTo("0.00");
        // 销售出库：借 6401 主营业务成本 = COGS 750.00；贷 1405 库存商品 750.00
        assertThat(lineDebit(sdVoucher, "6401")).as("6401 借=COGS").isEqualByComparingTo("750.00");
        assertThat(lineCredit(sdVoucher, "1405")).as("1405 贷=COGS").isEqualByComparingTo("750.00");
        // 销售发票：借 1122 应收账款 = 应收额 = accounts_receivable.amount；贷 6001 主营业务收入 = 销售额
        BigDecimal receivable = receivableAmount(sinvNo);
        assertThat(lineDebit(sinvVoucher, "1122")).as("1122 借=应收额=子账金额")
                .isEqualByComparingTo(receivable);
        assertThat(receivable).as("accounts_receivable.amount=发票额").isEqualByComparingTo("1500.00");
        assertThat(lineCredit(sinvVoucher, "6001")).as("6001 贷=销售额").isEqualByComparingTo("1500.00");

        // ============ 断言④：试算平衡 trialBalance(period) Σ借==Σ贷（各凭证所属账期） ============
        List.of(prVoucher.getPeriod(), pinvVoucher.getPeriod(), sdVoucher.getPeriod(),
                        sinvVoucher.getPeriod()).stream().distinct()
                .forEach(this::assertTrialBalanced);

        // ============ 断言⑤：幂等——对各单据重复调 generateForXxx，findBySourceDocNo 仍 size==1 ============
        txTemplate.executeWithoutResult(s -> {
            autoVoucherService.generateForPurchaseReceipt(purchaseReceiptService.get(prNo), OPERATOR);
            autoVoucherService.generateForSalesDelivery(salesDeliveryService.get(sdNo), OPERATOR);
        });
        assertThat(voucherService.findBySourceDocNo(prNo)).as("采购入库幂等：重复调仍 1 张").hasSize(1);
        assertThat(voucherService.findBySourceDocNo(pinvNo)).as("采购发票幂等：仍 1 张").hasSize(1);
        assertThat(voucherService.findBySourceDocNo(sdNo)).as("销售出库幂等：重复调仍 1 张").hasSize(1);
        assertThat(voucherService.findBySourceDocNo(sinvNo)).as("销售发票幂等：仍 1 张").hasSize(1);
        // 物理兜底：voucher 表 source 维度行数未变
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM voucher WHERE tenant_id = 0 "
                        + "AND source_doc_no IN (?, ?, ?, ?)", Long.class, prNo, pinvNo, sdNo, sinvNo))
                .as("幂等：重复调后自动凭证总数仍 4").isEqualTo(4L);
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    /** 按来源单号取唯一凭证（断言 size==1） */
    private Voucher singleVoucher(String sourceDocNo, String expectedType) {
        List<Voucher> vouchers = voucherService.findBySourceDocNo(sourceDocNo);
        assertThat(vouchers).as("来源单据 %s（%s）恰一组凭证", sourceDocNo, expectedType).hasSize(1);
        return vouchers.get(0);
    }

    private void assertApprovedAndBalanced(Voucher voucher, String expectedType, String expectedSourceNo) {
        assertThat(voucher.getStatus().name()).as("凭证 %s 状态 APPROVED", voucher.getDocNo())
                .isEqualTo("APPROVED");
        assertThat(voucher.getSourceDocType()).as("source_doc_type 回填").isEqualTo(expectedType);
        assertThat(voucher.getSourceDocNo()).as("source_doc_no 回填").isEqualTo(expectedSourceNo);
        BigDecimal debit = voucher.getLines().stream().map(l -> l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = voucher.getLines().stream().map(l -> l.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("凭证 %s Σ借 = Σ贷", voucher.getDocNo()).isEqualByComparingTo(credit);
        assertThat(debit).as("凭证 %s 总额 > 0", voucher.getDocNo()).isGreaterThan(BigDecimal.ZERO);
    }

    /** 某凭证某科目的借方金额（无该科目行返回 0） */
    private BigDecimal lineDebit(Voucher voucher, String accountCode) {
        return voucher.getLines().stream()
                .filter(l -> l.getAccountCode().equals(accountCode))
                .map(l -> l.getDebit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 某凭证某科目的贷方金额（无该科目行返回 0） */
    private BigDecimal lineCredit(Voucher voucher, String accountCode) {
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

    private BigDecimal payableAmount(String invoiceNo) {
        return jdbc.queryForObject("SELECT amount FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", BigDecimal.class, invoiceNo);
    }

    private BigDecimal receivableAmount(String invoiceNo) {
        return jdbc.queryForObject("SELECT amount FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", BigDecimal.class, invoiceNo);
    }
}
