package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.purchase.PurchaseOrderLineInput;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceiptLineInput;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * M8-T05 性能基线：单据过账 + 库存并发。默认（CI {@code integration-db}）跑有界冒烟：
 * 合成 4096 行流水后 EXPLAIN 热查询、8 并发库存入库、8 并发采购入库过账。
 * 百万行 / 50 并发仅当 {@code SJHERP_PERF_SCALE=full}（{@code ./deploy/perf.sh --full}）启用，
 * 避免拖垮 PR CI。合成流水走保留仓库 id，不绕过领域服务做业务过账。
 */
@Tag("integration-db")
class DocumentPostingPerfIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-perf";
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static PurchaseOrderService orderService;
    private static PurchaseReceiptService receiptService;
    private static long seededRows;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        txTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        inventoryService = context.getBean(TransactionalInventoryService.class);
        orderService = context.getBean(PurchaseOrderService.class);
        receiptService = context.getBean(PurchaseReceiptService.class);
        long started = System.nanoTime();
        seededRows = LedgerPerfSupport.seedSyntheticTransactions(jdbc, LedgerPerfSupport.SMOKE_ROWS);
        System.out.printf("[M8-T05] smoke seed rows=%d in %dms%n",
                seededRows, (System.nanoTime() - started) / 1_000_000L);
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
    @Import({AuditConfig.class, InventoryInfraConfig.class, PurchaseInfraConfig.class})
    static class TestConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertyPlaceholder() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(MYSQL.getJdbcUrl());
            config.setUsername(MYSQL.getUsername());
            config.setPassword(MYSQL.getPassword());
            config.setMaximumPoolSize(80);
            config.setMinimumIdle(4);
            config.setPoolName("it-m8-t05-perf");
            return new HikariDataSource(config);
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

    @Test
    void 热查询EXPLAIN命中既有与来源单号索引() {
        assertThat(seededRows).isGreaterThanOrEqualTo(LedgerPerfSupport.SMOKE_ROWS);
        LedgerPerfSupport.assertUsesIndex(jdbc,
                "SELECT id, warehouse_id, product_id, quantity, cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0 FOR UPDATE",
                "uk_inventory_balance_dim",
                LedgerPerfSupport.SEED_WAREHOUSE_ID, LedgerPerfSupport.SEED_PRODUCT_ID);
        LedgerPerfSupport.assertUsesIndex(jdbc,
                "SELECT id, unit_cost FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND unit_cost IS NOT NULL ORDER BY id DESC LIMIT 1",
                "idx_inventory_txn_dim",
                LedgerPerfSupport.SEED_WAREHOUSE_ID, LedgerPerfSupport.SEED_PRODUCT_ID);
        LedgerPerfSupport.assertUsesIndex(jdbc,
                "SELECT id FROM inventory_transaction WHERE tenant_id = 0 AND idempotency_key = ?",
                "uk_inventory_txn_idempotency",
                "PERFSEED:0");
        LedgerPerfSupport.assertUsesIndex(jdbc,
                "SELECT id, total_cost FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND src_doc_no = ? AND src_line_no = 1",
                "idx_inventory_txn_src",
                "PERF-0");
        LedgerPerfSupport.assertUsesIndex(jdbc,
                "SELECT id FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? ORDER BY id DESC LIMIT 20",
                "idx_inventory_txn_dim",
                LedgerPerfSupport.SEED_WAREHOUSE_ID, LedgerPerfSupport.SEED_PRODUCT_ID);
        var voucherPlan = jdbc.queryForList(
                "EXPLAIN SELECT vl.account_code, COALESCE(SUM(vl.debit), 0), COALESCE(SUM(vl.credit), 0) "
                        + "FROM voucher_line vl JOIN voucher v ON vl.voucher_id = v.id "
                        + "WHERE v.tenant_id = 0 AND v.period = ? AND v.status IN ('APPROVED','REVERSED') "
                        + "GROUP BY vl.account_code",
                "2026-01");
        String voucherKeys = voucherPlan.stream()
                .map(row -> String.valueOf(row.get("key")) + "," + String.valueOf(row.get("possible_keys")))
                .reduce("", (a, b) -> a + " " + b);
        assertThat(voucherKeys)
                .as("试算/科目余额 JOIN 应能用凭证或分录索引：%s", voucherPlan)
                .containsAnyOf("idx_voucher_period", "idx_voucher_status", "idx_voucher_line",
                        "uk_voucher", "PRIMARY");
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void 库存并发入库_8用户独立SKU_对账恒等式成立() throws Exception {
        runInventoryInboundWave(8, "smoke-inv");
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void 采购入库过账_8用户独立SKU_库存恒等式成立() throws Exception {
        runReceiptPostWave(8, "smoke-pr");
    }

    @Test
    @Tag("perf")
    @EnabledIfEnvironmentVariable(named = "SJHERP_PERF_SCALE", matches = "full")
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void 百万行流水后_50并发库存与采购过账() throws Exception {
        long started = System.nanoTime();
        long rows = LedgerPerfSupport.seedSyntheticTransactions(jdbc, LedgerPerfSupport.FULL_ROWS);
        System.out.printf("[M8-T05] full seed rows=%d in %dms%n",
                rows, (System.nanoTime() - started) / 1_000_000L);
        assertThat(rows).isGreaterThanOrEqualTo(LedgerPerfSupport.FULL_ROWS);
        LedgerPerfSupport.assertUsesIndex(jdbc,
                "SELECT id, unit_cost FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? "
                        + "AND unit_cost IS NOT NULL ORDER BY id DESC LIMIT 1",
                "idx_inventory_txn_dim",
                LedgerPerfSupport.SEED_WAREHOUSE_ID, LedgerPerfSupport.SEED_PRODUCT_ID);
        LedgerPerfSupport.assertUsesIndex(jdbc,
                "SELECT id, total_cost FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND src_doc_no = ? AND src_line_no = 1",
                "idx_inventory_txn_src",
                "PERF-0");
        runInventoryInboundWave(50, "full-inv");
        runReceiptPostWave(50, "full-pr");
    }

    private void runInventoryInboundWave(int users, String label) throws Exception {
        List<Sku> skus = new ArrayList<>(users);
        for (int i = 0; i < users; i++) {
            skus.add(new Sku(nextId(), nextId()));
        }
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Long> latencies = runConcurrently(users, thread -> () -> {
            Sku sku = skus.get(thread);
            String key = label + ":" + uniqueSuffix() + ":" + thread;
            long t0 = System.nanoTime();
            inventoryService.inbound(new InboundCommand(
                    sku.warehouseId, sku.productId, InventoryTxnType.PURCHASE_IN,
                    new BigDecimal("2"), new BigDecimal("10.00"), null,
                    "PERF_INV", "DOC-" + key, 1, key), OPERATOR);
            return (System.nanoTime() - t0) / 1_000_000L;
        }, errors);
        assertWave("库存入库 " + label, users, latencies, errors);
        for (Sku sku : skus) {
            LedgerPerfSupport.assertLedgerIdentity(jdbc, sku.warehouseId, sku.productId);
        }
    }

    private void runReceiptPostWave(int users, String label) throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 21);
        List<PreparedReceipt> prepared = new ArrayList<>(users);
        for (int i = 0; i < users; i++) {
            long supplierId = nextId();
            long warehouseId = nextId();
            long productId = nextId();
            String suffix = uniqueSuffix() + "-" + i;
            String poNo = "PO-PERF-" + suffix;
            String prNo = "PR-PERF-" + suffix;
            txTemplate.executeWithoutResult(status -> {
                orderService.create(poNo, supplierId, date, label,
                        List.of(new PurchaseOrderLineInput(productId, new BigDecimal("10"),
                                new BigDecimal("8.50"))), OPERATOR);
                orderService.approve(poNo, OPERATOR);
                receiptService.create(prNo, poNo, warehouseId, date, label,
                        List.of(new PurchaseReceiptLineInput(1, new BigDecimal("10"), null)), OPERATOR);
                receiptService.approve(prNo, OPERATOR);
            });
            prepared.add(new PreparedReceipt(prNo, warehouseId, productId));
        }
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Long> latencies = runConcurrently(users, thread -> () -> {
            PreparedReceipt doc = prepared.get(thread);
            long t0 = System.nanoTime();
            txTemplate.executeWithoutResult(status -> receiptService.post(doc.prNo, OPERATOR));
            return (System.nanoTime() - t0) / 1_000_000L;
        }, errors);
        assertWave("采购入库过账 " + label, users, latencies, errors);
        for (PreparedReceipt doc : prepared) {
            LedgerPerfSupport.assertLedgerIdentity(jdbc, doc.warehouseId, doc.productId);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM purchase_receipt WHERE tenant_id = 0 AND doc_no = ?",
                    String.class, doc.prNo)).isEqualTo("COMPLETED");
        }
    }

    private static void assertWave(String label, int users, List<Long> latencies, Queue<Throwable> errors) {
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        long p50 = LedgerPerfSupport.percentile(sorted, 0.50);
        long p95 = LedgerPerfSupport.percentile(sorted, 0.95);
        long max = sorted.isEmpty() ? 0L : sorted.get(sorted.size() - 1);
        System.out.printf("[M8-T05] %s users=%d p50=%dms p95=%dms max=%dms errors=%d%n",
                label, users, p50, p95, max, errors.size());
        assertThat(errors)
                .as("%s 并发失败：%s", label, errors)
                .isEmpty();
        assertThat(latencies).as("%s 应完成 %d 次", label, users).hasSize(users);
        assertThat(p95)
                .as("%s p95=%d 应小于 %d ms（CI 宽松上限；人工 --full 看打印值）",
                        label, p95, LedgerPerfSupport.P95_LIMIT_MS)
                .isLessThan(LedgerPerfSupport.P95_LIMIT_MS);
    }

    private static List<Long> runConcurrently(
            int threadCount,
            ThreadTask task,
            Queue<Throwable> errors) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        Queue<Long> latencies = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                final int thread = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        latencies.add(task.run(thread));
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("线程就位超时").isTrue();
            start.countDown();
            assertThat(done.await(300, TimeUnit.SECONDS))
                    .as("并发任务未在 300s 内完成").isTrue();
        } finally {
            pool.shutdownNow();
        }
        return List.copyOf(latencies);
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    @FunctionalInterface
    private interface ThreadTask {
        long run(int thread) throws Exception;
    }

    private record Sku(long warehouseId, long productId) {
    }

    private record PreparedReceipt(String prNo, long warehouseId, long productId) {
    }
}
