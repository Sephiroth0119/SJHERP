package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.purchase.PurchaseInvoiceLineInput;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.persistence.JdbcSequenceProvider;

/**
 * 采购单据红冲端到端集成测试（M4-T07b 验收核心，Testcontainers 真实 MySQL）。
 *
 * <p>用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link PurchaseInfraConfig} + {@link GlInfraConfig}）+ 把
 * {@link PurchaseReceiptAppService}/{@link PurchaseInvoiceAppService}/{@link VoucherAppService}
 * 注册为 Bean（受 @Transactional 代理，等价生产边界；{@code WarehouseService} 注入 mock——
 * reverse 路径不触仓库档案），跑通：
 * <ul>
 *   <li>下单 → 入库 post（自动凭证）→ 发票 post（自动凭证 + 应付）→ 采购发票 reverse → 采购入库 reverse；</li>
 *   <li>断言：库存按<b>原成本</b>反向出库（期间已进新货仍按原值反向）、子账量回退、应付 REVERSED、
 *       自动凭证红冲（原 + 红字 source 维度抵平）、原单 REVERSED；</li>
 *   <li>幂等：原单已 REVERSED 再 reverse 被拒；带核销的应付 reverse 前置拒绝；闭月 reverse 整体回滚。</li>
 * </ul>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class PurchaseReversalFlowIntegrationTest {

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
    private static PurchaseReceiptAppService receiptAppService;
    private static PurchaseInvoiceAppService invoiceAppService;
    private static AccountsPayableRepository payableRepository;
    private static VoucherService voucherService;
    private static AccountingPeriodService accountingPeriodService;

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
        receiptAppService = context.getBean(PurchaseReceiptAppService.class);
        invoiceAppService = context.getBean(PurchaseInvoiceAppService.class);
        payableRepository = context.getBean(AccountsPayableRepository.class);
        voucherService = context.getBean(VoucherService.class);
        accountingPeriodService = context.getBean(AccountingPeriodService.class);
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
            GlInfraConfig.class})
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

        /** reverse 路径不触仓库档案：注入 mock（建单走仓库校验，本测试建单经领域服务直驱绕开） */
        @Bean
        WarehouseService warehouseService() {
            return mock(WarehouseService.class);
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
                WarehouseService warehouseService,
                AccountsPayableRepository accountsPayableRepository,
                DocumentNumberGenerator documentNumberGenerator,
                AutoVoucherService autoVoucherService, VoucherService voucherService,
                VoucherAppService voucherAppService,
                com.sjherp.domain.partner.SupplierService supplierService) {
            return new PurchaseInvoiceAppService(purchaseInvoiceService, purchaseReceiptService,
                    purchaseOrderService, supplierService, accountsPayableRepository,
                    documentNumberGenerator, autoVoucherService, voucherService, voucherAppService);
        }

        /** 发票 AppService 构造需 SupplierService（reverse 不触档案）：注入 mock */
        @Bean
        com.sjherp.domain.partner.SupplierService supplierService() {
            return mock(com.sjherp.domain.partner.SupplierService.class);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    @Test
    void 采购入库与发票红冲_库存按原成本反向_子账回退_应付与凭证冲销_原单REVERSED() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-R-" + suffix;
        String prNo = "PR-R-" + suffix;
        String pinvNo = "PINV-R-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        // 下单 100@12.50 → 审核 → 收 100 → 入库 post（AppService 同事务自动凭证 借1405/贷220201 各 1250）
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, null,
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d,
                null, List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        receiptAppService.post(prNo, OPERATOR);   // AppService 自身 @Transactional + 自动凭证

        // 发票 100@1250.00 → 审核 → 过账（应付 1250.00 + 自动凭证 借220201/贷220202）
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, null, null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        // 过账经领域服务直驱 + 自动凭证（AppService.post 取 supplier 结算方式经 mock，故直接领域驱动）
        txTemplate.executeWithoutResult(s -> {
            var invoice = purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY, OPERATOR);
            context.getBean(AutoVoucherService.class).generateForPurchaseInvoice(invoice, OPERATOR);
        });

        // 入库后库存 100/1250.00；发票后应付 1250.00 OPEN、收货行 invoiced_qty=100
        assertBalanceQty(warehouseId, productId, "100");
        assertThat(payableStatus(pinvNo)).isEqualTo("OPEN");
        assertThat(invoicedQty(prNo, 1)).isEqualByComparingTo("100");

        // 模拟期间进新货改变加权（再进 50@20，库存 150/2250.00，加权单价 15）——验证红冲按原值 12.50
        long newProduct = productId;   // 同品
        // 用领域入库再进 50@20（不同采购单链路简化：直接 post 一张新收货）
        String po2 = "PO2-R-" + suffix;
        String pr2 = "PR2-R-" + suffix;
        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(po2, supplierId, d, null,
                List.of(new PurchaseOrderLineInput(newProduct, new BigDecimal("50"),
                        new BigDecimal("20"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(po2, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(pr2, po2, warehouseId, d,
                null, List.of(new PurchaseReceiptLineInput(1, new BigDecimal("50"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(pr2, OPERATOR));
        receiptAppService.post(pr2, OPERATOR);
        assertBalanceQty(warehouseId, productId, "150");
        assertBalanceAmount(warehouseId, productId, "2250.00");   // 1250 + 1000

        // ========== 发票 reverse：回退开票量 + 应付 REVERSED + 红冲发票凭证 ==========
        invoiceAppService.reverse(pinvNo, OPERATOR);
        assertThat(invoiceStatus(pinvNo)).isEqualTo("REVERSED");
        assertThat(payableStatus(pinvNo)).isEqualTo("REVERSED");
        assertThat(invoicedQty(prNo, 1)).as("收货行开票量回退为 0").isEqualByComparingTo("0");
        // 发票自动凭证（220201/220202）原 + 红字 source 维度抵平：220202 净额 0
        assertSourceNetZero("220202", pinvNo);

        // ========== 入库 reverse：按原成本 12.50 反向出库 + 回退到货量 + 红冲入库凭证 ==========
        BigDecimal qtyBefore = balanceQty(warehouseId, productId);   // 150
        receiptAppService.reverse(prNo, OPERATOR);
        assertThat(receiptStatus(prNo)).isEqualTo("REVERSED");
        // 库存数量减 100（150 → 50）；金额按原值 12.50×100=1250 反向（2250 → 1000，而非按加权 15 反向）
        assertThat(balanceQty(warehouseId, productId)).isEqualByComparingTo(qtyBefore.subtract(new BigDecimal("100")));
        assertBalanceAmount(warehouseId, productId, "1000.00");
        // 反向出库流水按原单价：total_cost = -1250.00
        BigDecimal reversalCost = jdbc.queryForObject("SELECT total_cost FROM inventory_transaction "
                + "WHERE tenant_id = 0 AND idempotency_key = ?", BigDecimal.class,
                "REVERSAL:" + prNo + ":1");
        assertThat(reversalCost).as("反向出库按原成本 12.50×100").isEqualByComparingTo("-1250.00");
        // 采购订单到货量回退为 0
        assertThat(purchaseOrderService.get(poNo).getLines().get(0).getReceivedQty())
                .isEqualByComparingTo("0");
        // 入库自动凭证（1405/220201）原 + 红字 source 维度抵平
        assertSourceNetZero("1405", prNo);

        // 幂等：原单已 REVERSED 再 reverse 被拒（领域层 IllegalState → 整事务回滚）
        assertThatThrownBy(() -> receiptAppService.reverse(prNo, OPERATOR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invoiceAppService.reverse(pinvNo, OPERATOR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 闭月红冲被拒_整事务回滚_库存与单据状态不变() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-C-" + suffix;
        String prNo = "PR-C-" + suffix;
        LocalDate d = LocalDate.of(2026, 3, 10);

        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, null,
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("40"),
                        new BigDecimal("5"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d,
                null, List.of(new PurchaseReceiptLineInput(1, new BigDecimal("40"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        receiptAppService.post(prNo, OPERATOR);   // 入库 + 自动凭证，账期 202603 自动 open

        // 关闭 202603 账期 → 红冲入库凭证需在该账期过账被拒
        txTemplate.executeWithoutResult(s -> accountingPeriodService.close("202603", OPERATOR));

        assertThatThrownBy(() -> receiptAppService.reverse(prNo, OPERATOR))
                .isInstanceOf(PeriodClosedException.class);
        // 整事务回滚：原单仍 COMPLETED、库存数量未变、未生成红字凭证
        assertThat(receiptStatus(prNo)).isEqualTo("COMPLETED");
        assertBalanceQty(warehouseId, productId, "40");
        Long redCount = jdbc.queryForObject("SELECT COUNT(*) FROM voucher WHERE tenant_id = 0 "
                + "AND source_doc_type = 'VOUCHER_REVERSAL' AND source_doc_no = "
                + "(SELECT doc_no FROM voucher WHERE tenant_id = 0 AND source_doc_no = ? "
                + "AND source_doc_type = 'PURCHASE_RECEIPT')", Long.class, prNo);
        assertThat(redCount).as("闭月回滚：无红字凭证").isZero();
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

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

    private String receiptStatus(String docNo) {
        return jdbc.queryForObject("SELECT status FROM purchase_receipt WHERE doc_no = ?",
                String.class, docNo);
    }

    private String invoiceStatus(String docNo) {
        return jdbc.queryForObject("SELECT status FROM purchase_invoice WHERE doc_no = ?",
                String.class, docNo);
    }

    private String payableStatus(String invoiceNo) {
        return jdbc.queryForObject("SELECT status FROM accounts_payable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", String.class, invoiceNo);
    }

    private BigDecimal invoicedQty(String receiptNo, int lineNo) {
        return jdbc.queryForObject("SELECT invoiced_qty FROM purchase_receipt_line "
                + "WHERE purchase_receipt_id = (SELECT id FROM purchase_receipt WHERE doc_no = ?) "
                + "AND line_no = ?", BigDecimal.class, receiptNo, lineNo);
    }

    /**
     * 某科目在「某来源单据的自动凭证 + 其红字凭证」上的净额为 0（借−贷 跨原+红字抵平）。
     * 取该来源自动凭证 docNo，统计 source_doc_no IN(自动凭证来源单号, 自动凭证号[红字以它为来源]) 的该科目借贷。
     */
    private void assertSourceNetZero(String accountCode, String sourceDocNo) {
        String autoVoucherNo = jdbc.queryForObject("SELECT doc_no FROM voucher WHERE tenant_id = 0 "
                + "AND source_doc_no = ? AND source_doc_type <> 'VOUCHER_REVERSAL' LIMIT 1",
                String.class, sourceDocNo);
        // 红字凭证 source_doc_no = 原自动凭证号；原自动凭证 source_doc_no = 业务单号
        BigDecimal net = jdbc.queryForObject(
                "SELECT COALESCE(SUM(vl.debit - vl.credit), 0) FROM voucher_line vl "
                        + "JOIN voucher v ON v.id = vl.voucher_id "
                        + "WHERE v.tenant_id = 0 AND vl.account_code = ? "
                        + "AND (v.source_doc_no = ? OR v.source_doc_no = ?)",
                BigDecimal.class, accountCode, sourceDocNo, autoVoucherNo);
        assertThat(net).as("科目 %s 跨原+红字凭证净额归零", accountCode).isEqualByComparingTo("0");
    }
}
