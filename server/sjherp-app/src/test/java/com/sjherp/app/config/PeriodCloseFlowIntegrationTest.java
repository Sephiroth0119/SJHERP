package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.sjherp.app.consistency.ConsistencyCheckDao;
import com.sjherp.app.consistency.ConsistencyCheckService;
import com.sjherp.app.gl.AutoVoucherService;
import com.sjherp.app.gl.GlDtos;
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.app.gl.PeriodCloseBlockedException;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.PeriodStatus;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLineInput;
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
 * 月末结转关账整月验收集成测试（M4-T05 验收主测，设计真源 §4 集成八步，Testcontainers 真实 MySQL）。
 *
 * <p>用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link PurchaseInfraConfig} + {@link SalesInfraConfig} + {@link GlInfraConfig}）跑通完整链路并验收
 * {@link PeriodCloseService} 的「结转前一致性闸门 → 损益结转凭证 → 试算平衡断言 → 关账」四步：
 * <ol>
 *   <li>Flyway 迁移（预置科目就位）→ 开账期 P；</li>
 *   <li>模拟一个月业务：采购下单→入库→发票→付款 + 销售下单→出库(COGS)→发票→收款（业务过账经 T02
 *       自动生成对应记账凭证——本测试在领域服务驱动后，于同事务内显式调
 *       {@link AutoVoucherService}，等价 AppService.post 的「过账 + 自动凭证」原子边界）；</li>
 *   <li>precheck(P)：closeable=true、无 ERROR、结转预览含 6001/6401、netProfit=毛利；</li>
 *   <li>close(P) 成功：结转凭证 APPROVED（来源 PERIOD_CLOSING/源单=P）、trialBalance(P) 中 6001/6401
 *       净额=0、4103 净额=收入−成本(毛利)、Σ借==Σ贷、账期 CLOSED；</li>
 *   <li>关账后该期再建+过账普通凭证 → {@link PeriodClosedException} 且回滚；</li>
 *   <li>重复 close(P) → {@link PeriodCloseBlockedException}（既存结转凭证防护）；</li>
 *   <li>直插脏数据（库存账实不平）→ close 被一致性闸门拒（ERROR reasons）、账期仍 OPEN 无结转凭证（回滚验证）；</li>
 *   <li>audit_log 有 period.close 与 voucher.post 记录。</li>
 * </ol>
 *
 * <p><b>账期取当前 UTC 月</b>：{@link AutoVoucherService#generateForSalesDelivery} 的 COGS 凭证日期
 * 取 {@code LocalDate.now(UTC)}（出库单无业务日），故全部业务凭证必须落在<b>当前 UTC 月</b>才同期可结转。
 * 本测试用 {@code YearMonth.now(UTC)} 派生账期 P，所有业务日期取该月内固定一日——无论运行时刻在哪个月
 * 都自洽（采购入库/采购发票/销售发票按业务日落该期，销售出库 COGS 按 now(UTC) 落同一期）。
 *
 * <p>驱动方式（与 {@link PurchaseToSalesFlowIntegrationTest} 一致）：经各<b>领域服务</b>直驱，自造
 * supplier/customer/warehouse/product id 隔离数据（库存/单据各表均无外键约束），不引入 catalog/warehouse
 * 全档案 Bean 闭包。状态流转/过账用 {@link TransactionTemplate} 提供外层事务（等价 AppService 的事务边界）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PeriodCloseFlowIntegrationTest {

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
    private static VoucherService voucherService;
    private static AccountingPeriodService periodService;
    private static PeriodCloseService periodCloseService;

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
        voucherService = context.getBean(VoucherService.class);
        periodService = context.getBean(AccountingPeriodService.class);
        periodCloseService = context.getBean(PeriodCloseService.class);
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

        // 凭证号生成器（GlInfraConfig 的 AutoVoucherService 依赖；生产由 CatalogInfraConfig 注册，
        // 此隔离上下文显式 new，与 GeneralLedgerPostingIntegrationTest 同款）。
        @Bean
        SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
            return new JdbcSequenceProvider(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
            return new DefaultDocumentNumberGenerator(sequenceProvider);
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

        // 月末结转关账编排器（生产 @Service 组件扫描；此处显式 new，注入顺序同生产构造器）
        @Bean
        PeriodCloseService periodCloseService(VoucherService voucherService,
                                              com.sjherp.domain.gl.AccountService accountService,
                                              AccountingPeriodService accountingPeriodService,
                                              ConsistencyCheckService consistencyCheckService,
                                              DocumentNumberGenerator documentNumberGenerator) {
            return new PeriodCloseService(voucherService, accountService, accountingPeriodService,
                    consistencyCheckService, documentNumberGenerator);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // ===============================================================
    // 验收主测：整月业务 → precheck → close → 关账生效 → 重复关账拒
    // （设计真源 §4 集成步 ①②③④⑤⑥ 一气呵成，单测试方法内顺序断言，账期态前后强约束）
    // ===============================================================

    @Test
    @Order(1)
    void 整月业务后关账成功_损益归零_本年利润等于毛利_关账后再过账被拒_重复关账被拒() {
        // 步①：账期取当前 UTC 月（COGS 凭证日 = now(UTC)，须与其余业务凭证同期）。
        YearMonth ym = YearMonth.now(ZoneOffset.UTC);
        String period = ym.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        // 业务日期取该月中段固定一日（确保落在 period 内，避免月末/月初边界与 now() 跨日竞态）。
        LocalDate bizDate = ym.atDay(Math.min(15, ym.lengthOfMonth()));

        long supplierId = nextId();
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String poNo = "PO-PC-" + suffix;
        String prNo = "PR-PC-" + suffix;
        String pinvNo = "PINV-PC-" + suffix;
        String soNo = "SO-PC-" + suffix;
        String sdNo = "SD-PC-" + suffix;
        String sinvNo = "SINV-PC-" + suffix;

        // 开账期 P（业务过账时各业务凭证落该期；T02 ensurePeriodExists 撞键容错，先行显式 open 更稳）
        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        assertThat(periodService.isOpen(period)).isTrue();

        // 步②：模拟一个月业务 —— 经领域服务驱动 + 同事务内显式 T02 自动凭证（等价 AppService.post 边界）。
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
                SettlementMethod.MONTHLY, bizDate, "INV-PC", null,
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

        // 旁证：业务自动凭证已落地（4 张：采购入库/采购发票/销售出库/销售发票，均 APPROVED）
        assertThat(voucherService.findBySourceDocNo(prNo)).hasSize(1);
        assertThat(voucherService.findBySourceDocNo(pinvNo)).hasSize(1);
        assertThat(voucherService.findBySourceDocNo(sdNo)).hasSize(1);
        assertThat(voucherService.findBySourceDocNo(sinvNo)).hasSize(1);

        // 收入 = 销售发票额 6001 1500.00；成本 = COGS 6401 750.00；毛利 = 750.00。
        BigDecimal expectedRevenue = new BigDecimal("1500.00");
        BigDecimal expectedExpense = new BigDecimal("750.00");
        BigDecimal expectedNetProfit = new BigDecimal("750.00"); // 毛利

        // 步③：precheck(P) —— 可关账、无 ERROR、结转预览含 6001/6401、netProfit=毛利。
        PeriodCloseReadiness readiness = periodCloseService.precheck(period);
        assertThat(readiness.period()).isEqualTo(period);
        assertThat(readiness.status()).isEqualTo(PeriodStatus.OPEN.name());
        assertThat(readiness.alreadyClosed()).isFalse();
        assertThat(readiness.consistencyErrors())
                .as("结转前无一致性 ERROR（整月业务勾稽平），实际：%s", readiness.consistencyErrors())
                .isEmpty();
        assertThat(readiness.closeable()).as("可关账").isTrue();
        assertThat(new BigDecimal(readiness.totalRevenue())).isEqualByComparingTo(expectedRevenue);
        assertThat(new BigDecimal(readiness.totalExpense())).isEqualByComparingTo(expectedExpense);
        assertThat(new BigDecimal(readiness.netProfit())).isEqualByComparingTo(expectedNetProfit);
        // 结转预览覆盖损益两科目 + 本年利润 4103（毛额展示，收入借、成本贷、4103 两腿）
        assertThat(readiness.closingPreviewLines())
                .extracting(GlDtos.ClosingPreviewLine::accountCode)
                .contains("6001", "6401", "4103");

        // 步④：close(P) 成功，并捕获结果。
        PeriodCloseResult result = txTemplate.execute(s ->
                periodCloseService.close(period, OPERATOR));
        assertThat(result).isNotNull();
        assertThat(result.period()).isEqualTo(period);
        assertThat(result.closingVoucherDocNo()).as("有损益发生额则结转凭证号非空").isNotNull();
        assertThat(new BigDecimal(result.totalRevenue())).isEqualByComparingTo(expectedRevenue);
        assertThat(new BigDecimal(result.totalExpense())).isEqualByComparingTo(expectedExpense);
        assertThat(new BigDecimal(result.netProfit())).isEqualByComparingTo(expectedNetProfit);
        assertThat(new BigDecimal(result.trialBalanceDebit()))
                .as("关账后 Σ借 == Σ贷")
                .isEqualByComparingTo(new BigDecimal(result.trialBalanceCredit()));
        assertThat(result.closedBy()).isEqualTo(OPERATOR);
        assertThat(result.closedAt()).isNotNull();

        // 结转凭证存在且 APPROVED、来源 PERIOD_CLOSING、源单号=账期键。
        List<Voucher> closings = voucherService.findBySourceDocNo(period);
        assertThat(closings).as("每账期恰一张结转凭证").hasSize(1);
        Voucher closing = closings.get(0);
        assertThat(closing.getDocNo()).isEqualTo(result.closingVoucherDocNo());
        assertThat(closing.getStatus().name()).as("结转凭证已过账").isEqualTo("APPROVED");
        assertThat(closing.getSourceDocType()).isEqualTo("PERIOD_CLOSING");
        assertThat(closing.getSourceDocNo()).isEqualTo(period);
        assertThat(closing.getPeriod()).isEqualTo(period);

        // 试算平衡（结转后）：6001、6401 本期净额=0（损益归零）；4103 净额 = 收入−成本（毛利，贷余）。
        List<AccountBalance> balances = voucherService.trialBalance(period);
        assertThat(netByCode(balances, "6001")).as("主营业务收入结转后净额=0").isEqualByComparingTo("0.00");
        assertThat(netByCode(balances, "6401")).as("主营业务成本结转后净额=0").isEqualByComparingTo("0.00");
        // 4103 净额 = Σ借 − Σ贷；收入转入贷 1500、费用转入借 750 → 净额 = 750 − 1500 = −750（贷余=盈利）。
        assertThat(netByCode(balances, "4103"))
                .as("本年利润净额 = 费用借 − 收入贷 = -(毛利)（贷方余额=盈利）")
                .isEqualByComparingTo(expectedNetProfit.negate());
        // 全期 Σ借 == Σ贷（含业务凭证 + 结转凭证）。
        BigDecimal totalDebit = balances.stream().map(AccountBalance::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = balances.stream().map(AccountBalance::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).as("结转后全期试算平衡").isEqualByComparingTo(totalCredit);

        // 账期 CLOSED。
        assertThat(periodService.isOpen(period)).as("关账后账期 CLOSED").isFalse();
        assertThat(periodService.get(period).getStatus()).isEqualTo(PeriodStatus.CLOSED);

        // 步⑧（与本流相关部分）：审计 —— period.close（关账）与 voucher.post（结转凭证过账）落库。
        Long periodCloseAudit = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log "
                        + "WHERE action = 'period.close' AND target_code = ?",
                Long.class, period);
        assertThat(periodCloseAudit).as("period.close 审计记录").isGreaterThanOrEqualTo(1L);
        Long closingPostAudit = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log "
                        + "WHERE action = 'voucher.post' AND target_code = ?",
                Long.class, closing.getDocNo());
        assertThat(closingPostAudit).as("结转凭证 voucher.post 审计记录").isGreaterThanOrEqualTo(1L);

        // 步⑤：关账后该期再建 + 过账普通凭证 → PeriodClosedException 且回滚（验证关账真生效）。
        //       建单允许（账期只需存在），过账被拒；建单 + 过账同事务，异常时整事务回滚不留草稿。
        String afterCloseVch = "VCH-AC-" + suffix;
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s -> {
            voucherService.create(afterCloseVch, period, bizDate, "关账后凭证",
                    List.of(new VoucherLineInput("1001", new BigDecimal("10.00"), null, null),
                            new VoucherLineInput("6001", null, new BigDecimal("10.00"), null)),
                    OPERATOR);
            voucherService.post(afterCloseVch, OPERATOR);
        })).isInstanceOf(PeriodClosedException.class);
        Long afterCloseCount = jdbc.queryForObject("SELECT COUNT(*) FROM voucher "
                + "WHERE tenant_id = 0 AND doc_no = ?", Long.class, afterCloseVch);
        assertThat(afterCloseCount).as("关账期被拒凭证回滚，库中无痕").isZero();

        // 步⑥：重复 close(P) → PeriodCloseBlockedException（既存结转凭证幂等/安全防护）。
        //       账期已 CLOSED + 既存结转凭证两道闸门均会拒；断言异常类型 + reasons 非空。
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                periodCloseService.close(period, OPERATOR)))
                .isInstanceOf(PeriodCloseBlockedException.class)
                .satisfies(ex -> assertThat(((PeriodCloseBlockedException) ex).getReasons())
                        .as("被拒携原因清单").isNotEmpty());
        // 账期仍 CLOSED、结转凭证仍恰一张（重复关账不产生第二张结转凭证）。
        assertThat(periodService.get(period).getStatus()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(voucherService.findBySourceDocNo(period)).hasSize(1);
    }

    // ===============================================================
    // 步⑦：脏数据闸门 —— 直插库存账实不平 → close 被一致性闸门拒（ERROR reasons），
    //      账期仍 OPEN、无结转凭证（整事务回滚验证）。
    // ===============================================================
    //
    // 本测试 @Order(2) 在主测之后跑，且用<b>独立固定账期</b>（不依赖 now(UTC)：无销售出库，
    // 不受 COGS 凭证日约束），与主测的当月账期解耦——主测先在干净库上完成关账，本测试再注入
    // 脏数据；脏数据为全库 tenant-0 永久污染（直插绕过领域服务、自动提交不回滚），故必须排在
    // 主测之后（@TestMethodOrder 强约束），避免污染主测的一致性闸门。
    // 一致性校验 check() 全库口径、与账期无关，故关哪个 OPEN 账期都会撞同一 LEDGER_QUANTITY ERROR。

    @Test
    @Order(2)
    void 库存账实不平时关账被一致性闸门拒_账期仍OPEN无结转凭证() {
        // 独立固定账期（远期，绝不与当前 UTC 月重合；本期不会有任何业务/结转凭证）。
        String period = "209912";
        String suffix = Long.toString(System.nanoTime(), 36);
        long warehouseId = nextId();
        long productId = nextId();

        // 开账期（远期独占，必不存在 → 直接 open）。
        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        assertThat(periodService.isOpen(period)).isTrue();

        // 期初入 50 / 500.00（流水与余额一致），随后篡改余额数量制造账实不平（绕过领域服务，仅造反例）。
        inventoryService.inbound(new com.sjherp.domain.inventory.InboundCommand(warehouseId, productId,
                com.sjherp.domain.inventory.InventoryTxnType.OPENING, new BigDecimal("50"),
                new BigDecimal("10.00"), null, "OPENING", "OP-PCDIRTY-" + suffix, 1,
                "OPENING:OP-PCDIRTY-" + suffix + ":1"), OPERATOR);
        jdbc.update("UPDATE inventory_balance SET quantity = quantity + 7 "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);

        // close 被一致性闸门拒：抛 PeriodCloseBlockedException，reasons 含 LEDGER_QUANTITY ERROR 摘要。
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                periodCloseService.close(period, OPERATOR)))
                .isInstanceOf(PeriodCloseBlockedException.class)
                .satisfies(ex -> {
                    List<String> reasons = ((PeriodCloseBlockedException) ex).getReasons();
                    assertThat(reasons).as("携 ERROR 原因清单").isNotEmpty();
                    assertThat(reasons).as("含库存数量恒等式 ERROR 摘要")
                            .anyMatch(r -> r.contains("LEDGER_QUANTITY")
                                    && r.contains("warehouse=" + warehouseId + ",product=" + productId));
                });

        // 回滚验证（独立账期，断言无条件成立）：闸门在结转/关账之前拒 → 账期仍 OPEN、本期无结转凭证。
        assertThat(periodService.isOpen(period))
                .as("闸门拒后账期仍 OPEN（结转/关账均未发生）").isTrue();
        assertThat(periodService.get(period).getStatus()).isEqualTo(PeriodStatus.OPEN);
        assertThat(voucherService.findBySourceDocNo(period))
                .as("脏数据下绝不留半结转凭证").isEmpty();
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    /** 取某科目本期净额（Σ借−Σ贷，2 位）；无该科目发生额返回 0.00。 */
    private static BigDecimal netByCode(List<AccountBalance> balances, String code) {
        return balances.stream()
                .filter(b -> b.accountCode().equals(code))
                .map(AccountBalance::netBalance)
                .findFirst()
                .orElse(new BigDecimal("0.00"));
    }
}
