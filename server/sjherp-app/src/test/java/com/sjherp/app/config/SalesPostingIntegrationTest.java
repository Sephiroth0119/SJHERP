package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLineInput;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceLineInput;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderLineInput;
import com.sjherp.domain.sales.SalesOrderService;

/**
 * 销售线过账整链集成测试（M3-T08/T09/T10 验收核心，Testcontainers 真实 MySQL）：
 * 用生产同套装配（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig} +
 * {@link SalesInfraConfig}）跑通：
 * <ul>
 *   <li>期初入库 → 下单 → 审核 → 部分出库过账（SALES_OUT）→ 断言库存减少、SALES_OUT 流水、
 *       COGS 取移动加权写到出库行、SO 累计发货量更新；对账恒等式 Σ流水=余额；</li>
 *   <li>开票 → 过账 → 应收生成（OPEN，金额=发票额，来源=发票号，客户正确）；</li>
 *   <li>另测库存不足出库被拒整批回滚（无 SALES_OUT 流水残留、余额不变、出库单未完成）。</li>
 * </ul>
 *
 * <p>不直接走 app 的 AppService（避免装配 catalog/warehouse/partner 档案 Bean 与真实档案行）——
 * 销售/库存表均无外键约束，测试自造 warehouse_id/product_id/customer_id 隔离数据，单据状态流转与
 * 库存/应收过账用 {@link TransactionTemplate} 提供外层事务（等价各 AppService 的 @Transactional 边界）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class SalesPostingIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static SalesOrderService salesOrderService;
    private static SalesDeliveryService salesDeliveryService;
    private static SalesInvoiceService salesInvoiceService;

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
        salesOrderService = context.getBean(SalesOrderService.class);
        salesDeliveryService = context.getBean(SalesDeliveryService.class);
        salesInvoiceService = context.getBean(SalesInvoiceService.class);
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
    @Import({AuditConfig.class, InventoryInfraConfig.class, SalesInfraConfig.class})
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

    @Test
    void 销售整链_期初到出库到开票_COGS与应收与对账恒等式() {
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String orderNo = "SO-IT-" + suffix;
        String deliveryNo = "SD-IT-" + suffix;
        String invoiceNo = "SINV-IT-" + suffix;

        // 1. 期初入库：100 个 @10.00、再入 80 @12.50 → 余额 180 个 / 2000.00（移动加权单价 11.111111）
        inventoryService.inbound(opening(warehouseId, productId, "100", "10.00", suffix + "A"), OPERATOR);
        inventoryService.inbound(purchaseIn(warehouseId, productId, "80", "12.50", suffix + "B"), OPERATOR);

        // 2. 下单（客户 customerId，商品 productId 100 个 @20.00 售价）+ 审核
        txTemplate.executeWithoutResult(s -> salesOrderService.create(orderNo, customerId,
                java.time.LocalDate.of(2026, 6, 13), "整链",
                List.of(new SalesOrderLineInput(productId, new BigDecimal("100"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(orderNo, OPERATOR));

        // 3. 部分出库：发 70 个 → 出库过账（SALES_OUT）
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(deliveryNo, orderNo, warehouseId,
                "首批", List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("70"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(deliveryNo, OPERATOR));
        SalesDelivery posted = txTemplate.execute(s -> salesDeliveryService.post(deliveryNo, OPERATOR));

        // SALES_OUT 流水产生
        assertThat(txnType(warehouseId, productId)).containsExactly("OPENING", "PURCHASE_IN", "SALES_OUT");
        // 余额减少：180 → 110
        assertBalanceQty(warehouseId, productId, "110");

        // COGS = 移动加权出库成本：单价 2000.00/180 = 11.111111，×70 = 777.78（HALF_UP），记到出库行
        BigDecimal cogs = posted.getLines().get(0).getCogsAmount();
        assertThat(cogs).as("出库行 COGS").isEqualByComparingTo("777.78");
        // 库存出库流水 total_cost（负数）绝对值 = COGS
        BigDecimal salesOutTotal = jdbc.queryForObject("SELECT total_cost FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND txn_type = 'SALES_OUT'",
                BigDecimal.class, warehouseId, productId);
        assertThat(salesOutTotal).isEqualByComparingTo("-777.78");

        // SO 累计发货量更新：行1 已发 70、剩余 30
        BigDecimal deliveredQty = jdbc.queryForObject("SELECT delivered_qty FROM sales_order_line "
                        + "WHERE sales_order_id = (SELECT id FROM sales_order WHERE doc_no = ?) AND line_no = 1",
                BigDecimal.class, orderNo);
        assertThat(deliveredQty).isEqualByComparingTo("70");

        // 对账恒等式：Σ流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额
        assertLedgerIdentity(warehouseId, productId);

        // 4. 开票：对出库行 70 个 @25.00 开票 → 过账生成应收
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(invoiceNo, deliveryNo, customerId,
                java.time.LocalDate.of(2026, 6, 14), java.time.LocalDate.of(2026, 7, 14), "开票",
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("70"), new BigDecimal("25"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(invoiceNo, OPERATOR));
        SalesInvoice postedInvoice = txTemplate.execute(s -> salesInvoiceService.post(invoiceNo, OPERATOR));
        assertThat(postedInvoice.getStatus().name()).isEqualTo("COMPLETED");
        // 发票金额 70 × 25 = 1750.00
        assertThat(postedInvoice.totalAmount()).isEqualByComparingTo("1750.00");

        // 应收生成：OPEN、金额=发票额、来源=发票号、客户正确
        var ar = jdbc.queryForMap("SELECT customer_id, amount, status, source_doc_no "
                + "FROM accounts_receivable WHERE tenant_id = 0 AND source_doc_no = ?", invoiceNo);
        assertThat(((Number) ar.get("customer_id")).longValue()).isEqualTo(customerId);
        assertThat((BigDecimal) ar.get("amount")).isEqualByComparingTo("1750.00");
        assertThat(ar.get("status")).isEqualTo("OPEN");
    }

    @Test
    void 库存不足出库被拒整批回滚_无残留() {
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String orderNo = "SO-ITX-" + suffix;
        String deliveryNo = "SD-ITX-" + suffix;

        // 期初只入 10 个
        inventoryService.inbound(opening(warehouseId, productId, "10", "10.00", suffix + "A"), OPERATOR);

        // 下单 50 个 + 审核
        txTemplate.executeWithoutResult(s -> salesOrderService.create(orderNo, customerId,
                java.time.LocalDate.of(2026, 6, 13), null,
                List.of(new SalesOrderLineInput(productId, new BigDecimal("50"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(orderNo, OPERATOR));

        // 出库 50（超过现存 10）+ 审核
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(deliveryNo, orderNo, warehouseId,
                null, List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("50"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(deliveryNo, OPERATOR));

        // 过账：库存不足整批回滚（外层事务回滚）
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> salesDeliveryService.post(deliveryNo, OPERATOR)))
                .isInstanceOf(InsufficientStockException.class);

        // 无 SALES_OUT 流水残留、余额不变（仍 10）
        assertThat(txnType(warehouseId, productId)).containsExactly("OPENING");
        assertBalanceQty(warehouseId, productId, "10");
        // 出库单未完成（回滚后状态仍是 APPROVED——startExecution 与 complete 随事务回滚）
        assertThat(jdbc.queryForObject("SELECT status FROM sales_delivery WHERE doc_no = ?",
                String.class, deliveryNo)).isEqualTo("APPROVED");
        // SO 累计发货量未更新
        BigDecimal deliveredQty = jdbc.queryForObject("SELECT delivered_qty FROM sales_order_line "
                        + "WHERE sales_order_id = (SELECT id FROM sales_order WHERE doc_no = ?) AND line_no = 1",
                BigDecimal.class, orderNo);
        assertThat(deliveredQty).isEqualByComparingTo("0");
    }

    @Test
    void 跨发票超额开票_发票1全额过账后发票2再开同出库行被拒_不生成第二笔应收() {
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String orderNo = "SO-ITI-" + suffix;
        String deliveryNo = "SD-ITI-" + suffix;
        String sinv1 = "SINV1-ITI-" + suffix;
        String sinv2 = "SINV2-ITI-" + suffix;

        // 期初入 100 → 下单 100 → 审核 → 发 70 出库过账
        inventoryService.inbound(opening(warehouseId, productId, "100", "10.00", suffix + "A"), OPERATOR);
        txTemplate.executeWithoutResult(s -> salesOrderService.create(orderNo, customerId,
                java.time.LocalDate.of(2026, 6, 13), null,
                List.of(new SalesOrderLineInput(productId, new BigDecimal("100"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(orderNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(deliveryNo, orderNo, warehouseId,
                null, List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("70"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(deliveryNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.post(deliveryNo, OPERATOR));

        // 发票1：对出库行 70 全额开票 → 审核 → 过账（回写出库行 invoiced_qty=70，生成第一笔应收）
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(sinv1, deliveryNo, customerId,
                java.time.LocalDate.of(2026, 6, 14), java.time.LocalDate.of(2026, 7, 14), null,
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("70"), new BigDecimal("25"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(sinv1, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.post(sinv1, OPERATOR));
        // 出库行已开票量回写为 70
        assertThat(jdbc.queryForObject("SELECT invoiced_qty FROM sales_delivery_line "
                        + "WHERE sales_delivery_id = (SELECT id FROM sales_delivery WHERE doc_no = ?) "
                        + "AND line_no = 1", BigDecimal.class, deliveryNo)).isEqualByComparingTo("70");

        // 发票2：同出库行再开（剩余可开票量 = 0）→ 建单即被拒，不生成第二笔应收
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(s ->
                salesInvoiceService.create(sinv2, deliveryNo, customerId,
                        java.time.LocalDate.of(2026, 6, 14), java.time.LocalDate.of(2026, 7, 14), null,
                        List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("70"),
                                new BigDecimal("25"))), OPERATOR)))
                .isInstanceOf(IllegalArgumentException.class);
        // 发票2无应收；发票1有 1 笔应收
        Long sinv2Ar = jdbc.queryForObject("SELECT COUNT(*) FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, sinv2);
        assertThat(sinv2Ar).isZero();
        Long sinv1Ar = jdbc.queryForObject("SELECT COUNT(*) FROM accounts_receivable "
                + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, sinv1);
        assertThat(sinv1Ar).isEqualTo(1L);
    }

    @Test
    void 同一发票并发过账仅一次生效_出库累计和应收不重复() throws Exception {
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String orderNo = "SO-ITC-" + suffix;
        String deliveryNo = "SD-ITC-" + suffix;
        String invoiceNo = "SINV-ITC-" + suffix;

        inventoryService.inbound(opening(warehouseId, productId, "20", "10.00", suffix), OPERATOR);
        txTemplate.executeWithoutResult(s -> salesOrderService.create(orderNo, customerId,
                java.time.LocalDate.of(2026, 8, 2), null,
                List.of(new SalesOrderLineInput(productId, new BigDecimal("10"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(orderNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(deliveryNo, orderNo, warehouseId,
                null, List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("10"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(deliveryNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.post(deliveryNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.create(invoiceNo, deliveryNo, customerId,
                java.time.LocalDate.of(2026, 8, 2), null, null,
                List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("4"), new BigDecimal("25"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(invoiceNo, OPERATOR));

        CountDownLatch firstPosted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptingPost = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SalesInvoice> first = executor.submit(() -> txTemplate.execute(status -> {
                SalesInvoice result = salesInvoiceService.post(invoiceNo, OPERATOR);
                firstPosted.countDown();
                await(releaseFirst);
                return result;
            }));
            assertThat(firstPosted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> second = executor.submit(() -> {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        secondAttemptingPost.countDown();
                        salesInvoiceService.post(invoiceNo, OPERATOR);
                    });
                    return null;
                } catch (Throwable cause) {
                    return cause;
                }
            });
            assertThat(secondAttemptingPost.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatus().name()).isEqualTo("COMPLETED");
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("状态");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("SELECT invoiced_qty FROM sales_delivery_line "
                        + "WHERE sales_delivery_id = (SELECT id FROM sales_delivery WHERE tenant_id = 0 AND doc_no = ?) "
                        + "AND tenant_id = 0 AND line_no = 1", BigDecimal.class, deliveryNo))
                .isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts_receivable "
                        + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, invoiceNo))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM sales_invoice "
                        + "WHERE tenant_id = 0 AND doc_no = ?", String.class, invoiceNo))
                .isEqualTo("COMPLETED");
    }

    @Test
    void 不同发票并发争用同一出库剩余量_仅首笔成功且无死锁() throws Exception {
        long warehouseId = nextId();
        long productId = nextId();
        long customerId = nextId();
        String suffix = Long.toString(System.nanoTime(), 36);
        String orderNo = "SO-ITD-" + suffix;
        String deliveryNo = "SD-ITD-" + suffix;
        String firstInvoiceNo = "SINV1-ITD-" + suffix;
        String secondInvoiceNo = "SINV2-ITD-" + suffix;

        inventoryService.inbound(opening(warehouseId, productId, "20", "10.00", suffix), OPERATOR);
        txTemplate.executeWithoutResult(s -> salesOrderService.create(orderNo, customerId,
                java.time.LocalDate.of(2026, 8, 2), null,
                List.of(new SalesOrderLineInput(productId, new BigDecimal("10"), new BigDecimal("20"))),
                OPERATOR));
        txTemplate.executeWithoutResult(s -> salesOrderService.approve(orderNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.create(deliveryNo, orderNo, warehouseId,
                null, List.of(new SalesDeliveryLineInput(1, productId, new BigDecimal("10"))), OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.approve(deliveryNo, OPERATOR));
        txTemplate.executeWithoutResult(s -> salesDeliveryService.post(deliveryNo, OPERATOR));
        for (String invoiceNo : List.of(firstInvoiceNo, secondInvoiceNo)) {
            txTemplate.executeWithoutResult(s -> salesInvoiceService.create(invoiceNo, deliveryNo, customerId,
                    java.time.LocalDate.of(2026, 8, 2), null, null,
                    List.of(new SalesInvoiceLineInput(1, productId, new BigDecimal("6"),
                            new BigDecimal("25"))), OPERATOR));
            txTemplate.executeWithoutResult(s -> salesInvoiceService.approve(invoiceNo, OPERATOR));
        }

        CountDownLatch firstPosted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttemptingPost = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> txTemplate.executeWithoutResult(status -> {
                salesInvoiceService.post(firstInvoiceNo, OPERATOR);
                firstPosted.countDown();
                await(releaseFirst);
            }));
            assertThat(firstPosted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> second = executor.submit(() -> {
                try {
                    txTemplate.executeWithoutResult(status -> {
                        secondAttemptingPost.countDown();
                        salesInvoiceService.post(secondInvoiceNo, OPERATOR);
                    });
                    return null;
                } catch (Throwable cause) {
                    return cause;
                }
            });
            assertThat(secondAttemptingPost.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("累计已开票量");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(jdbc.queryForObject("SELECT invoiced_qty FROM sales_delivery_line "
                        + "WHERE sales_delivery_id = (SELECT id FROM sales_delivery WHERE tenant_id = 0 AND doc_no = ?) "
                        + "AND tenant_id = 0 AND line_no = 1", BigDecimal.class, deliveryNo))
                .isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_invoice "
                        + "WHERE tenant_id = 0 AND doc_no = ?", String.class, firstInvoiceNo))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_invoice "
                        + "WHERE tenant_id = 0 AND doc_no = ?", String.class, secondInvoiceNo))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts_receivable "
                        + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, firstInvoiceNo))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts_receivable "
                        + "WHERE tenant_id = 0 AND source_doc_no = ?", Long.class, secondInvoiceNo))
                .isZero();
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static InboundCommand opening(long warehouseId, long productId, String quantity,
                                          String unitCost, String key) {
        return new InboundCommand(warehouseId, productId, InventoryTxnType.OPENING,
                new BigDecimal(quantity), new BigDecimal(unitCost), null,
                "OPENING", "OP-IT-" + key, 1, "OPENING:OP-IT-" + key + ":1");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("等待释放销售发票过账事务超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待销售发票过账事务时被中断", exception);
        }
    }

    private static InboundCommand purchaseIn(long warehouseId, long productId, String quantity,
                                             String unitCost, String key) {
        return new InboundCommand(warehouseId, productId, InventoryTxnType.PURCHASE_IN,
                new BigDecimal(quantity), new BigDecimal(unitCost), null,
                "PURCHASE_IN", "PI-IT-" + key, 1, "PURCHASE_IN:PI-IT-" + key + ":1");
    }

    private List<String> txnType(long warehouseId, long productId) {
        return jdbc.queryForList("SELECT txn_type FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? ORDER BY id",
                String.class, warehouseId, productId);
    }

    private void assertBalanceQty(long warehouseId, long productId, String expected) {
        BigDecimal qty = jdbc.queryForObject("SELECT quantity FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                BigDecimal.class, warehouseId, productId);
        assertThat(qty).as("商品 %d 余额数量", productId).isEqualByComparingTo(expected);
    }

    private void assertLedgerIdentity(long warehouseId, long productId) {
        var sums = jdbc.queryForMap("SELECT SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum "
                        + "FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                warehouseId, productId);
        var balance = jdbc.queryForMap("SELECT quantity, cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        assertThat((BigDecimal) sums.get("qty_sum")).as("商品 %d Σ流水数量 = 余额数量", productId)
                .isEqualByComparingTo((BigDecimal) balance.get("quantity"));
        assertThat((BigDecimal) sums.get("cost_sum")).as("商品 %d Σ流水金额 = 余额金额", productId)
                .isEqualByComparingTo((BigDecimal) balance.get("cost_amount"));
    }
}
