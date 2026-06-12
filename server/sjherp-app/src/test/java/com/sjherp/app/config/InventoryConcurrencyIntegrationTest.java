package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * 库存并发一致性专项（M3-T01d，拆解 docs/M3拆解-库存与成本.md §3 T01d 与 §1.4 并发策略）：
 * Testcontainers 真实 MySQL（REPEATABLE READ）+ 多线程，装配照抄
 * {@link InventoryPostingIntegrationTest}（生产同构：@Import 真实 {@link AuditConfig} +
 * {@link InventoryInfraConfig}，仅基础设施 Bean 由测试提供）。四类场景：
 * <ol>
 *   <li><b>混合并发一致性</b>：20 线程对同一 (warehouse, product) 并发混合出入库
 *       （参数固定种子预生成、可复算）→ 对账恒等式 Σ流水 quantity = 余额数量、
 *       Σ流水 total_cost = 余额金额；流水条数 = 成功操作数（无丢失更新）；</li>
 *   <li><b>防超卖</b>：现存 10，5 线程并发各出 3 → 行锁串行化下恰 3 成功，
 *       失败线程收到 {@link InsufficientStockException}，终态数量 ≥ 0 且恒等式成立；</li>
 *   <li><b>死锁回归</b>：调拨 A→B 与 B→A（各为一笔 execute 批量：出+入两腿）并发
 *       循环 50 次 → 升序锁约定（§1.4）下零死锁、全部成功、两仓恒等式与金额守恒成立。
 *       <b>不做死锁重试</b>：约定本身防死锁，出现 DeadlockLoserDataAccessException
 *       即测试失败（这是回归信号，不是可吞噪声）；</li>
 *   <li><b>幂等并发</b>：同一 idempotencyKey 的入库 8 线程并发提交 → 恰一条流水落库、
 *       余额只累计一次；行锁串行化 + 锁后重放判定（InventoryService 与 execute 同序），
 *       其余线程拿到与首次一致的结果，IdempotencyConflict 不出现（参数相同）。</li>
 * </ol>
 *
 * <p>工程约定：CountDownLatch 对齐起跑；每线程每操作经 {@link TransactionalInventoryService}
 * 独立事务；线程内异常收集到并发安全队列统一断言（不吞）；@Timeout 防 CI 卡死
 * （innodb_lock_wait_timeout 默认 50s，卡锁会先于超时暴露为异常）。
 *
 * <p>连接池：用 Hikari（生产同款）而非 DriverManagerDataSource——20 工作线程 ×
 * （业务事务连接 + afterCommit 审计 REQUIRES_NEW 第二连接）瞬时峰值约 40，
 * maximumPoolSize=40 覆盖峰值（MySQL 容器默认 max_connections=151，余量充足）。
 *
 * <p>各场景先<b>串行</b>期初建账再并发——余额行已存在，并发 FOR UPDATE 走记录锁
 * 完全串行化；避开"零行并发首插"的间隙锁（gap lock）死锁场景（生产中期初建账
 * 同样先行，该场景不在 v1.0 约定内）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无
 * Docker）。CI 的 backend-integration-db job 显式运行：
 * <pre>mvn test -pl sjherp-infra,sjherp-app -Dgroups=integration-db -DexcludedGroups=none</pre>
 */
@Tag("integration-db")
class InventoryConcurrencyIntegrationTest {

    /** MySQL 8.4（与 InventoryPostingIntegrationTest / MySqlContainerTestBase 同版本约定） */
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-concurrent";

    /** 混合场景固定随机种子：操作序列预生成可复算（失败可本地重放同一序列） */
    private static final long MIXED_PLAN_SEED = 20260613L;

    /** 仓库/商品 id 发号器（两表无外键约束，集成测试即造 id 隔离各用例数据） */
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionalInventoryService inventoryService;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        DataSource migrationDataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        // 跑 classpath 全部迁移（db/migration 来自 sjherp-infra 依赖，含 V10 两表）
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        inventoryService = context.getBean(TransactionalInventoryService.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();  // 连同 HikariDataSource 一起关闭（destroyMethod 推断）
        }
        // 容器由 Testcontainers Ryuk 自动回收
    }

    /**
     * 与生产同套装配（照抄 InventoryPostingIntegrationTest）：审计与库存两个真实
     * @Configuration 原样 @Import，测试只补数据源/JdbcTemplate/事务管理器。
     * 数据源用 Hikari（并发场景必须真连接池，池量见类注释）。
     */
    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({AuditConfig.class, InventoryInfraConfig.class})
    static class TestConfig {

        /** InventoryInfraConfig 的 @Value 占位符解析（默认值即生产默认：禁负库存/移动加权） */
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
            // 20 工作线程 × 2 连接（业务事务 + afterCommit 审计 REQUIRES_NEW）瞬时峰值 40
            config.setMaximumPoolSize(40);
            config.setMinimumIdle(4);
            config.setPoolName("it-inventory-concurrency");
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

    // ---------------------------------------------------------------
    // ① 混合并发一致性：20 线程同行混合出入库
    // ---------------------------------------------------------------

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void 混合并发_20线程同行出入库_对账恒等式成立_无丢失更新() throws Exception {
        int threadCount = 20;
        int opsPerThread = 5;
        long warehouseId = nextId();
        long productId = nextId();
        String run = "MIX:" + uniqueSuffix();

        // 期初足够大，排除合法性（库存不足）对并发断言的干扰
        inventoryService.inbound(inboundCmd(warehouseId, productId, InventoryTxnType.OPENING,
                "1000000", "10.00", run + ":OPEN"), OPERATOR);

        // 参数预生成（固定种子可复算）：随机入库（1..9 个 @0.01..20.00）或出库（1..9 个）
        Random random = new Random(MIXED_PLAN_SEED);
        List<List<StockMovementCommand>> plans = new ArrayList<>(threadCount);
        BigDecimal expectedQuantity = new BigDecimal("1000000");
        for (int t = 0; t < threadCount; t++) {
            List<StockMovementCommand> ops = new ArrayList<>(opsPerThread);
            for (int i = 0; i < opsPerThread; i++) {
                String key = run + ":" + t + ":" + i;
                BigDecimal quantity = BigDecimal.valueOf(random.nextInt(9) + 1L);
                if (random.nextBoolean()) {
                    BigDecimal unitCost = BigDecimal.valueOf(random.nextInt(2000) + 1L, 2);
                    ops.add(new InboundCommand(warehouseId, productId, InventoryTxnType.PURCHASE_IN,
                            quantity, unitCost, null, "IT_MIX", "DOC-" + key, 1, key));
                    expectedQuantity = expectedQuantity.add(quantity);
                } else {
                    ops.add(new OutboundCommand(warehouseId, productId, InventoryTxnType.SALES_OUT,
                            quantity, "IT_MIX", "DOC-" + key, 1, key));
                    expectedQuantity = expectedQuantity.subtract(quantity);
                }
            }
            plans.add(ops);
        }

        List<Throwable> errors = runConcurrently(threadCount, thread -> () -> {
            // 每线程按预生成序列逐笔提交，每笔一个独立事务（TransactionalInventoryService）
            for (StockMovementCommand command : plans.get(thread)) {
                switch (command) {
                    case InboundCommand in -> inventoryService.inbound(in, OPERATOR);
                    case OutboundCommand out -> inventoryService.outbound(out, OPERATOR);
                    default -> throw new IllegalStateException("混合场景只生成出入库: " + command);
                }
            }
        });

        assertThat(errors).as("混合并发全部操作必须成功（期初足够大）: %s", describe(errors)).isEmpty();
        // 无丢失更新：流水条数 = 期初 1 + 提交成功操作数 100
        assertThat(transactionCount(warehouseId, productId))
                .isEqualTo(1L + threadCount * opsPerThread);
        // 对账恒等式 + 终态数量等于预生成序列的确定性合计
        BalanceSnapshot balance = assertAccountingIdentity(warehouseId, productId);
        assertThat(balance.quantity()).isEqualByComparingTo(expectedQuantity);
        assertThat(balance.costAmount()).as("默认禁负库存下结存金额恒 ≥ 0")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------
    // ② 防超卖：现存 10，5 线程并发各出 3
    // ---------------------------------------------------------------

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void 防超卖_现存10并发5线程各出3_恰3成功_其余库存不足_无负库存() throws Exception {
        int threadCount = 5;
        long warehouseId = nextId();
        long productId = nextId();
        String run = "SELL:" + uniqueSuffix();

        inventoryService.inbound(inboundCmd(warehouseId, productId, InventoryTxnType.OPENING,
                "10", "5.00", run + ":OPEN"), OPERATOR);

        AtomicInteger successCount = new AtomicInteger();
        Queue<Throwable> insufficiencies = new ConcurrentLinkedQueue<>();
        List<Throwable> errors = runConcurrently(threadCount, thread -> () -> {
            try {
                inventoryService.outbound(new OutboundCommand(warehouseId, productId,
                        InventoryTxnType.SALES_OUT, new BigDecimal("3"),
                        "IT_SELL", "DOC-" + run + ":" + thread, 1, run + ":" + thread), OPERATOR);
                successCount.incrementAndGet();
            } catch (InsufficientStockException expected) {
                // 预期失败形态：库存不足领域异常（其他任何异常都进 errors 统一暴露）
                insufficiencies.add(expected);
            }
        });

        assertThat(errors).as("失败线程只允许收到 InsufficientStockException: %s", describe(errors))
                .isEmpty();
        // FOR UPDATE 行锁完全串行化 → 确定性恰 3 成功（10→7→4→1，第 4/5 个事务看到 1 < 3）
        assertThat(successCount.get()).as("成功线程数 = ⌊10/3⌋ = 3").isEqualTo(3);
        assertThat(insufficiencies).hasSize(threadCount - 3);
        // 终态：无负库存击穿，恒等式成立（期初 1 笔 + 成功 3 笔）
        BalanceSnapshot balance = assertAccountingIdentity(warehouseId, productId);
        assertThat(balance.quantity()).isEqualByComparingTo("1");
        assertThat(balance.quantity()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(balance.costAmount()).isEqualByComparingTo("5.00");
        assertThat(transactionCount(warehouseId, productId)).as("被拒出库无流水残留").isEqualTo(4);
    }

    // ---------------------------------------------------------------
    // ③ 死锁回归：调拨 A→B 与 B→A 并发循环（升序锁约定 §1.4）
    // ---------------------------------------------------------------

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void 死锁回归_双向调拨并发循环50次_零死锁_全部成功_两仓恒等式与金额守恒() throws Exception {
        int iterations = 50;
        // nextId() 单调递增 → warehouseA < warehouseB；execute 内部按升序锁与 id 大小无关地排序，
        // 两方向事务的加锁顺序一致（A 先 B 后），这正是本用例回归的约定
        long warehouseA = nextId();
        long warehouseB = nextId();
        long productId = nextId();
        String run = "TR:" + uniqueSuffix();

        inventoryService.inbound(inboundCmd(warehouseA, productId, InventoryTxnType.OPENING,
                "1000", "10.00", run + ":OPEN-A"), OPERATOR);
        inventoryService.inbound(inboundCmd(warehouseB, productId, InventoryTxnType.OPENING,
                "1000", "12.00", run + ":OPEN-B"), OPERATOR);

        // 两个线程：0 = A→B，1 = B→A；每次迭代一笔 execute 批量（出+入两腿同事务）。
        // 不做死锁重试：升序锁约定本身防死锁，出现 DeadlockLoserDataAccessException
        // 应直接进 errors 让测试失败（约定被破坏的回归信号）
        List<Throwable> errors = runConcurrently(2, thread -> () -> {
            long from = thread == 0 ? warehouseA : warehouseB;
            long to = thread == 0 ? warehouseB : warehouseA;
            String label = thread == 0 ? "AB" : "BA";
            for (int i = 0; i < iterations; i++) {
                String outKey = run + ":" + label + ":" + i + ":OUT";
                String inKey = run + ":" + label + ":" + i + ":IN";
                String docNo = "TR-" + label + "-" + i;
                inventoryService.execute(List.of(
                        new OutboundCommand(from, productId, InventoryTxnType.TRANSFER_OUT,
                                BigDecimal.ONE, "TRANSFER", docNo, 1, outKey),
                        new InboundCommand(to, productId, InventoryTxnType.TRANSFER_IN,
                                BigDecimal.ONE, null, outKey, "TRANSFER", docNo, 2, inKey)),
                        OPERATOR);
            }
        });

        assertThat(errors).as("双向调拨并发不得出现死锁/锁超时等任何异常: %s", describe(errors))
                .isEmpty();
        // 全部成功：每仓 = 期初 1 + 调出 50 + 调入 50 = 101 笔流水
        assertThat(transactionCount(warehouseA, productId)).isEqualTo(1L + iterations * 2);
        assertThat(transactionCount(warehouseB, productId)).isEqualTo(1L + iterations * 2);
        // 两仓各自恒等式成立；等量对调后数量复原
        BalanceSnapshot balanceA = assertAccountingIdentity(warehouseA, productId);
        BalanceSnapshot balanceB = assertAccountingIdentity(warehouseB, productId);
        assertThat(balanceA.quantity()).isEqualByComparingTo("1000");
        assertThat(balanceB.quantity()).isEqualByComparingTo("1000");
        // 调拨金额守恒（§1.6.5 调入取调出原值）：两仓金额合计 = 期初合计，分仓金额随交错次序浮动
        assertThat(balanceA.costAmount().add(balanceB.costAmount()))
                .as("两仓金额合计守恒 = 1000×10.00 + 1000×12.00")
                .isEqualByComparingTo("22000.00");
    }

    // ---------------------------------------------------------------
    // ④ 幂等并发：同一 idempotencyKey 8 线程并发提交
    // ---------------------------------------------------------------

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void 幂等并发_同键8线程并发入库_恰一条流水_其余线程拿到首次结果_余额只累计一次() throws Exception {
        int threadCount = 8;
        long warehouseId = nextId();
        long productId = nextId();
        String run = "IDEMC:" + uniqueSuffix();

        inventoryService.inbound(inboundCmd(warehouseId, productId, InventoryTxnType.OPENING,
                "100", "10.00", run + ":SEED"), OPERATOR);

        // 8 线程提交完全相同的指令（同键同参——同一单据行的并发过账重试形态）
        InboundCommand command = inboundCmd(warehouseId, productId, InventoryTxnType.PURCHASE_IN,
                "5", "4.00", run + ":LINE");
        Queue<StockMovementResult> results = new ConcurrentLinkedQueue<>();
        List<Throwable> errors = runConcurrently(threadCount, thread -> () ->
                results.add(inventoryService.inbound(command, OPERATOR)));

        // 锁后重放判定（InventoryService 与 execute 同序）下：行锁串行化，后到事务
        // 必然读到首笔流水并返回首次结果——既无 DuplicateKey 回滚，也无
        // IdempotencyConflictException（参数相同不构成冲突）
        assertThat(errors).as("同键同参并发提交不得抛任何异常: %s", describe(errors)).isEmpty();
        assertThat(results).hasSize(threadCount);
        assertThat(results.stream().map(StockMovementResult::transactionId).distinct())
                .as("所有线程拿到同一笔流水（首次结果）").hasSize(1);
        for (StockMovementResult result : results) {
            assertThat(result.quantity()).isEqualByComparingTo("5");
            assertThat(result.unitCost()).isEqualByComparingTo("4.000000");
            assertThat(result.totalCost()).isEqualByComparingTo("20.00");
            assertThat(result.balanceQuantityAfter()).isEqualByComparingTo("105");
            assertThat(result.balanceAmountAfter()).isEqualByComparingTo("1020.00");
        }
        // 恰一条流水落库（种子 1 + 本键 1），余额只累计一次，恒等式成立
        assertThat(transactionCount(warehouseId, productId)).isEqualTo(2);
        BalanceSnapshot balance = assertAccountingIdentity(warehouseId, productId);
        assertThat(balance.quantity()).isEqualByComparingTo("105");
        assertThat(balance.costAmount()).isEqualByComparingTo("1020.00");
    }

    // ---------------------------------------------------------------
    // 并发执行骨架：CountDownLatch 对齐起跑，异常收集不吞
    // ---------------------------------------------------------------

    /**
     * 启动 threadCount 个线程同时起跑（ready/start 两段闸门），收集每线程抛出的
     * 异常统一返回（任务内未自行捕获的一切 Throwable）；done 等待超时视为疑似
     * 死锁/锁等待挂起，直接断言失败而非无限等。
     */
    private static List<Throwable> runConcurrently(int threadCount, IntFunction<Runnable> tasks)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                Runnable task = tasks.apply(i);
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.run();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("线程就位超时").isTrue();
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS))
                    .as("并发任务未在 120s 内完成（疑似死锁或锁等待挂起）").isTrue();
        } finally {
            pool.shutdownNow();
        }
        return List.copyOf(errors);
    }

    /** 异常队列的可读描述（断言失败信息用，保留类型与文案） */
    private static String describe(List<Throwable> errors) {
        return errors.stream()
                .map(t -> t.getClass().getSimpleName() + "(" + t.getMessage() + ")")
                .toList()
                .toString();
    }

    // ---------------------------------------------------------------
    // 对账与计数工具（SQL 口径与检查 Agent M6-T06 一致）
    // ---------------------------------------------------------------

    /** 余额行快照（对账断言的返回载体） */
    private record BalanceSnapshot(BigDecimal quantity, BigDecimal costAmount) {
    }

    /**
     * 对账恒等式断言（拆解 §1.2 带符号设计）：Σ流水 quantity = 余额数量 且
     * Σ流水 total_cost = 余额金额；返回余额快照供进一步断言终态数字。
     */
    private BalanceSnapshot assertAccountingIdentity(long warehouseId, long productId) {
        Map<String, Object> sums = jdbc.queryForMap(
                "SELECT SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum "
                        + "FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? "
                        + "AND product_id = ?", warehouseId, productId);
        Map<String, Object> balance = jdbc.queryForMap(
                "SELECT quantity, cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        BigDecimal quantity = (BigDecimal) balance.get("quantity");
        BigDecimal costAmount = (BigDecimal) balance.get("cost_amount");
        assertThat((BigDecimal) sums.get("qty_sum"))
                .as("对账恒等式：Σ流水数量 = 余额数量（仓库 %d 商品 %d）", warehouseId, productId)
                .isEqualByComparingTo(quantity);
        assertThat((BigDecimal) sums.get("cost_sum"))
                .as("对账恒等式：Σ流水金额 = 余额金额（仓库 %d 商品 %d）", warehouseId, productId)
                .isEqualByComparingTo(costAmount);
        return new BalanceSnapshot(quantity, costAmount);
    }

    private long transactionCount(long warehouseId, long productId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                Long.class, warehouseId, productId);
        return count == null ? -1 : count;
    }

    // ---------------------------------------------------------------
    // 指令构造
    // ---------------------------------------------------------------

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static InboundCommand inboundCmd(long warehouseId, long productId, InventoryTxnType type,
                                             String quantity, String unitCost, String key) {
        return new InboundCommand(warehouseId, productId, type, new BigDecimal(quantity),
                unitCost == null ? null : new BigDecimal(unitCost), null,
                "IT_CONC", "DOC-" + key, 1, key);
    }
}
