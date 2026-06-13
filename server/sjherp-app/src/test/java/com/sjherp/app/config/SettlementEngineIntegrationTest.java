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

import com.sjherp.domain.common.OverSettlementException;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.payable.PayableNotFoundException;
import com.sjherp.domain.purchase.PurchaseInvoiceLineInput;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.domain.receivable.ReceivableNotFoundException;
import com.sjherp.domain.sales.SalesDeliveryLineInput;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoiceLineInput;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderLineInput;
import com.sjherp.domain.sales.SalesOrderService;
import com.sjherp.domain.settlement.SettlementRecord;
import com.sjherp.domain.settlement.SettlementRecordRepository;
import com.sjherp.domain.settlement.SettlementService;
import com.sjherp.domain.settlement.SettlementType;

/**
 * 核销引擎真库集成测试（M4-T03 验收①，Testcontainers 真实 MySQL）。
 *
 * <p>用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link PurchaseInfraConfig} + {@link SalesInfraConfig} + {@link SettlementInfraConfig}）跑通：
 * 经采购/销售发票过账真实生成应付/应收（沿用 {@link PurchaseToSalesFlowIntegrationTest} 的发票驱动方式），
 * 再经 {@link SettlementService} 做<b>部分 + 全额</b>核销，断言：
 * <ul>
 *   <li>子账 {@code settled_amount}/{@code status} 落库可重读（OPEN → PARTIAL → SETTLED 正确推进）；</li>
 *   <li>{@code settlement_record} 行数/金额/target 追溯（targetId/targetSourceDocNo）/{@code payment_doc_no}(null) 正确；</li>
 *   <li>超额核销抛 {@link OverSettlementException} 且 DB 态不变（事务回滚，子账金额/状态保持核销前）。</li>
 * </ul>
 *
 * <p>应付/应收子账无外键约束，supplier/customer/warehouse/product id 自造隔离（同 PurchaseToSalesFlow）。
 * 核销写动作经 {@link TransactionTemplate} 提供外层事务（等价 M4-T04 收付款单 AppService 的事务边界）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class SettlementEngineIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-settle";

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
    private static SettlementRecordRepository settlementRecordRepository;

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
        settlementRecordRepository = context.getBean(SettlementRecordRepository.class);
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
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // =================================================================================
    // 验收①-应收：部分 + 全额核销 → 状态 OPEN→PARTIAL→SETTLED 落库、核销记录追溯、payment_doc_no=null
    // =================================================================================

    @Test
    void 应收核销_部分加全额_状态推进落库且核销记录追溯正确() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String sinvNo = "SINV-SE-" + suffix;

        // 经发票过账真实生成应收（销售 60@25 → 应收 1500.00）；先备货 100。
        long arId = createReceivableViaInvoicePosting(supplierId, warehouseId, productId, customerId,
                suffix, sinvNo);
        assertSubledgerReceivable(arId, "1500.00", "0.00", "OPEN");

        LocalDate settleDate = LocalDate.of(2026, 6, 14);

        // ---- 第一笔：部分核销 600.00 → PARTIAL，已核销 600.00 ----
        SettlementRecord rec1 = txTemplate.execute(s ->
                settlementService.settleReceivable(arId, new BigDecimal("600.00"), settleDate, null, OPERATOR));
        assertThat(rec1).isNotNull();
        assertThat(rec1.getId()).as("核销记录落库回填 id").isNotNull();
        assertSubledgerReceivable(arId, "1500.00", "600.00", "PARTIAL");

        // ---- 第二笔：再部分核销 400.00 → 累加到 1000.00，仍 PARTIAL ----
        txTemplate.executeWithoutResult(s ->
                settlementService.settleReceivable(arId, new BigDecimal("400.00"), settleDate, null, OPERATOR));
        assertSubledgerReceivable(arId, "1500.00", "1000.00", "PARTIAL");

        // ---- 第三笔：补齐剩余 500.00 → 累加到 1500.00 = amount → SETTLED ----
        txTemplate.executeWithoutResult(s ->
                settlementService.settleReceivable(arId, new BigDecimal("500.00"), settleDate, null, OPERATOR));
        assertSubledgerReceivable(arId, "1500.00", "1500.00", "SETTLED");

        // ---- 核销记录：3 行、按发生先后、target 追溯、金额、payment_doc_no=null、createdBy=operator ----
        List<SettlementRecord> records = settlementService.findReceivableSettlements(arId);
        assertThat(records).as("应收核销记录应有 3 行").hasSize(3);
        assertThat(records).extracting(r -> r.getAmount().toPlainString())
                .containsExactly("600.00", "400.00", "500.00");
        for (SettlementRecord r : records) {
            assertThat(r.getType()).isEqualTo(SettlementType.RECEIVABLE);
            assertThat(r.getTargetId()).as("核销记录 targetId 追溯到应收主键").isEqualTo(arId);
            assertThat(r.getTargetSourceDocNo()).as("核销记录追溯到来源发票号").isEqualTo(sinvNo);
            assertThat(r.getPaymentDocNo()).as("T03 阶段 payment_doc_no 恒 null").isNull();
            assertThat(r.getCreatedBy()).isEqualTo(OPERATOR);
            assertThat(r.getSettlementDate()).isEqualTo(settleDate);
        }
        // DB 旁证：核销记录金额合计 == 子账已核销额；payment_doc_no 全为 NULL
        BigDecimal recordSum = jdbc.queryForObject(
                "SELECT SUM(amount) FROM settlement_record WHERE tenant_id = 0 "
                        + "AND settlement_type = 'RECEIVABLE' AND target_id = ?", BigDecimal.class, arId);
        assertThat(recordSum).as("Σ核销记录金额 = 子账已核销额").isEqualByComparingTo("1500.00");
        Long nonNullPayment = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_record WHERE tenant_id = 0 AND target_id = ? "
                        + "AND settlement_type = 'RECEIVABLE' AND payment_doc_no IS NOT NULL",
                Long.class, arId);
        assertThat(nonNullPayment).as("T03 阶段无任何 payment_doc_no 回填").isZero();
    }

    // =================================================================================
    // 验收①-应付：全额一次核销直接 SETTLED；超额核销被拒、DB 态不变（事务回滚）
    // =================================================================================

    @Test
    void 应付核销_全额一次SETTLED_超额被拒且DB态不变() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String pinvNo = "PINV-SE-" + suffix;

        // 经发票过账真实生成应付（采购 100@12.50 → 应付 1250.00）。
        long apId = createPayableViaInvoicePosting(supplierId, warehouseId, productId, suffix, pinvNo);
        assertSubledgerPayable(apId, "1250.00", "0.00", "OPEN");

        LocalDate settleDate = LocalDate.of(2026, 6, 14);

        // ---- 全额一次核销 1250.00 → 直接 SETTLED ----
        txTemplate.executeWithoutResult(s ->
                settlementService.settlePayable(apId, new BigDecimal("1250.00"), settleDate, null, OPERATOR));
        assertSubledgerPayable(apId, "1250.00", "1250.00", "SETTLED");

        List<SettlementRecord> records = settlementService.findPayableSettlements(apId);
        assertThat(records).hasSize(1);
        SettlementRecord only = records.get(0);
        assertThat(only.getType()).isEqualTo(SettlementType.PAYABLE);
        assertThat(only.getTargetId()).isEqualTo(apId);
        assertThat(only.getTargetSourceDocNo()).isEqualTo(pinvNo);
        assertThat(only.getAmount()).isEqualByComparingTo("1250.00");
        assertThat(only.getPaymentDocNo()).isNull();

        // ---- 超额核销：已 SETTLED（outstanding=0），再核销 0.01 必抛 OverSettlementException ----
        //      settle 在任何 repo.save 之前即校验越界先抛 → 子账 UPDATE 与核销记录 INSERT 均未发生；
        //      外层事务回滚为纵深防御（本断言证明的是"压根未写入"，而非"写入后回滚"——勿高估其强度）。
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s ->
                        settlementService.settlePayable(apId, new BigDecimal("0.01"), settleDate, null, OPERATOR)))
                .isInstanceOf(OverSettlementException.class);

        // DB 态不变：子账仍 1250.00/SETTLED；核销记录仍 1 行（settle 越界先抛、子账与记录根本未写入）
        assertSubledgerPayable(apId, "1250.00", "1250.00", "SETTLED");
        Long recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_record WHERE tenant_id = 0 "
                        + "AND settlement_type = 'PAYABLE' AND target_id = ?", Long.class, apId);
        assertThat(recordCount).as("超额核销被拒后核销记录数不变").isEqualTo(1L);
    }

    // =================================================================================
    // 验收①-边界：部分核销后超额（剩余余额之外）被拒、DB 回滚到部分态；不存在子账抛 NotFound
    // =================================================================================

    @Test
    void 应收超额核销被拒_回滚到部分态_不存在子账抛NotFound() {
        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String sinvNo = "SINV-OVF-" + suffix;

        long arId = createReceivableViaInvoicePosting(supplierId, warehouseId, productId, customerId,
                suffix, sinvNo);

        LocalDate settleDate = LocalDate.of(2026, 6, 14);
        // 先部分核销 1000.00 → PARTIAL，剩余 500.00
        txTemplate.executeWithoutResult(s ->
                settlementService.settleReceivable(arId, new BigDecimal("1000.00"), settleDate, null, OPERATOR));
        assertSubledgerReceivable(arId, "1500.00", "1000.00", "PARTIAL");

        // 再核销 500.01（剩余 500.00 之外 0.01）→ settle 越界先抛 OverSettlementException（在写库之前）
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s ->
                        settlementService.settleReceivable(arId, new BigDecimal("500.01"), settleDate, null, OPERATOR)))
                .isInstanceOf(OverSettlementException.class);

        // 子账仍 1000.00/PARTIAL；核销记录仍 1 行（越界先抛、第二笔根本未写入）
        assertSubledgerReceivable(arId, "1500.00", "1000.00", "PARTIAL");
        Long recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_record WHERE tenant_id = 0 "
                        + "AND settlement_type = 'RECEIVABLE' AND target_id = ?", Long.class, arId);
        assertThat(recordCount).as("超额被拒后核销记录数保持 1").isEqualTo(1L);

        // 不存在的子账 → NotFound（应收/应付各自异常）
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s ->
                        settlementService.settleReceivable(999_000_001L, new BigDecimal("1.00"),
                                settleDate, null, OPERATOR)))
                .isInstanceOf(ReceivableNotFoundException.class);
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s ->
                        settlementService.settlePayable(999_000_002L, new BigDecimal("1.00"),
                                settleDate, null, OPERATOR)))
                .isInstanceOf(PayableNotFoundException.class);
    }

    // =================================================================================
    // 验收①-补：findByPaymentDocNo 真库 SQL 路径（T03 服务恒写 payment_doc_no=null，此查询是 T04/T07
    // 红冲反查的依赖，须独立造非空样本验证其真实 SQL——否则列名/排序拼写错要到 T04 才暴露）
    // =================================================================================

    @Test
    void 核销记录_按收付款单号查_命中并排除NULL与其它单号() {
        String payNo = "PAY-FBP-" + Long.toString(System.nanoTime(), 36);
        long t1 = nextId();
        long t2 = nextId();
        long t3 = nextId();
        long t4 = nextId();
        // 同一收付款单号核销两笔（应收 + 应付各一），另插 payment_doc_no=NULL 与别的单号各一作干扰
        insertSettlementRecord("RECEIVABLE", t1, "SINV-FBP-1", "100.00", payNo);
        insertSettlementRecord("PAYABLE", t2, "PINV-FBP-2", "200.00", payNo);
        insertSettlementRecord("RECEIVABLE", t3, "SINV-FBP-3", "300.00", null);             // NULL 不应命中
        insertSettlementRecord("RECEIVABLE", t4, "SINV-FBP-4", "400.00", payNo + "-OTHER");  // 别的单号不命中

        List<SettlementRecord> hit = settlementRecordRepository.findByPaymentDocNo(payNo);

        assertThat(hit).as("按收付款单号命中两条（NULL 与其它单号被排除）").hasSize(2);
        assertThat(hit).extracting(SettlementRecord::getPaymentDocNo).containsOnly(payNo);
        // ORDER BY id → 按插入先后；字段映射完整性旁证
        assertThat(hit).extracting(r -> r.getAmount().toPlainString()).containsExactly("100.00", "200.00");
        assertThat(hit).extracting(SettlementRecord::getTargetId).containsExactly(t1, t2);
        assertThat(hit.get(0).getType()).isEqualTo(SettlementType.RECEIVABLE);
        assertThat(hit.get(1).getType()).isEqualTo(SettlementType.PAYABLE);
        assertThat(hit.get(0).getTargetSourceDocNo()).isEqualTo("SINV-FBP-1");
    }

    /** 直插核销记录（仅本验证用：T03 服务恒写 payment_doc_no=null，需手造非空单号样本）。 */
    private void insertSettlementRecord(String type, long targetId, String sourceDocNo,
                                        String amount, String paymentDocNo) {
        jdbc.update("INSERT INTO settlement_record (tenant_id, settlement_type, target_id, "
                        + "target_source_doc_no, amount, settlement_date, payment_doc_no, created_by, created_at) "
                        + "VALUES (0, ?, ?, ?, ?, ?, ?, ?, NOW(6))",
                type, targetId, sourceDocNo, new BigDecimal(amount),
                java.sql.Date.valueOf(LocalDate.of(2026, 6, 14)), paymentDocNo, OPERATOR);
    }

    // ---------------------------------------------------------------
    // 夹具：经真实发票过账链路生成应收/应付（沿用 PurchaseToSalesFlowIntegrationTest 的驱动方式）
    // ---------------------------------------------------------------

    /** 采购 100@12.50 整链过账后返回生成的应付主键（应付 1250.00，来源 = 采购发票号）。 */
    private long createPayableViaInvoicePosting(long supplierId, long warehouseId, long productId,
                                                String suffix, String pinvNo) {
        String poNo = "PO-SE-" + suffix;
        String prNo = "PR-SE-" + suffix;
        LocalDate d = LocalDate.of(2026, 6, 13);

        txTemplate.executeWithoutResult(s -> purchaseOrderService.create(poNo, supplierId, d, "核销夹具采购",
                List.of(new PurchaseOrderLineInput(productId, new BigDecimal("100"),
                        new BigDecimal("12.50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseOrderService.approve(poNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.create(prNo, poNo, warehouseId, d, "收货",
                List.of(new PurchaseReceiptLineInput(1, new BigDecimal("100"), null)), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.approve(prNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseReceiptService.post(prNo, OPERATOR));

        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.create(pinvNo, prNo, supplierId,
                SettlementMethod.MONTHLY, d, "INV-SE", null,
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
    private long createReceivableViaInvoicePosting(long supplierId, long warehouseId, long productId,
                                                  long customerId, String suffix, String sinvNo) {
        String poNo = "PO-SE-R-" + suffix;
        String prNo = "PR-SE-R-" + suffix;
        String pinvNo = "PINV-SE-R-" + suffix;
        String soNo = "SO-SE-R-" + suffix;
        String sdNo = "SD-SE-R-" + suffix;
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
                SettlementMethod.MONTHLY, d, "INV-SE-R", null,
                List.of(new PurchaseInvoiceLineInput(1, new BigDecimal("100"),
                        new BigDecimal("1250.00"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.approve(pinvNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> purchaseInvoiceService.post(pinvNo, SettlementMethod.MONTHLY,
                OPERATOR));

        // 销售：60@20 下单 → 出库过账 → 60@25 发票过账（应收 1500.00）
        txTemplate.executeWithoutResult(s -> salesOrderService.create(soNo, customerId, d, "核销夹具销售",
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

    // ---------------------------------------------------------------
    // 断言工具：直接重读子账行（验证 settled_amount/status 已落库可重读）
    // ---------------------------------------------------------------

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
}
