package com.sjherp.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.sjherp.domain.common.numbering.SequenceProvider;

/**
 * JdbcSequenceProvider 真实数据库集成测试（M2-T02 验收：行锁取号并发安全、重启不重号）。
 *
 * <p>默认不执行：@Tag("integration") 被父 POM 的 excludedGroups=integration 排除。
 * 本地手动运行（需 V2 迁移已生效的 MySQL，默认指向开发 VM）：
 * <pre>mvn test -pl sjherp-infra -Dgroups=integration -DexcludedGroups=none</pre>
 *
 * <p>连接参数与 sjherp-app application.yml 默认值一致，可用环境变量
 * SJHERP_DB_URL / SJHERP_DB_USERNAME / SJHERP_DB_PASSWORD 覆盖；数据库不可达时跳过不算失败。
 *
 * <p>生产中 @Transactional(REQUIRES_NEW) 由 Spring 代理生效；本测试用
 * TransactionTemplate 显式包裹每次取号，复现同样的"一次取号一个独立事务"语义。
 */
@Tag("integration")
class JdbcSequenceProviderIntegrationTest {

    /** 测试专用作用域键（按时间戳隔离多次运行；TESTSEQ 前缀与业务前缀不冲突） */
    private final String scopeKey = "TESTSEQ-" + System.currentTimeMillis();

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;
    private SequenceProvider provider;

    @BeforeEach
    void setUp() {
        String url = envOrDefault("SJHERP_DB_URL", "jdbc:mysql://192.168.237.133:3306/sjherp");
        String username = envOrDefault("SJHERP_DB_USERNAME", "sjherp_app");
        String password = envOrDefault("SJHERP_DB_PASSWORD", "sjherp_dev_2026");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(dataSource);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        provider = new JdbcSequenceProvider(jdbc);

        // 数据库不可达或 V2 未生效（doc_sequence 不存在）时跳过，不算失败
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM doc_sequence", Integer.class);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "数据库不可达或 doc_sequence 表不存在，跳过集成测试: " + e.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            try {
                jdbc.update("DELETE FROM doc_sequence WHERE scope_key = ?", scopeKey);
            } catch (Exception ignored) {
                // 清理失败不影响测试结论（作用域键带时间戳，不会污染下次运行）
            }
        }
    }

    /** 串行取号：从 1 开始严格 +1 */
    @Test
    void sequentialNumbersAreStrictlyIncreasingFromOne() {
        List<Long> numbers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            numbers.add(nextInNewTransaction(provider));
        }
        assertThat(numbers).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    /** 并发取号：8 线程 × 25 次，行锁保证无重号无空洞（本场景无业务回滚） */
    @Test
    void concurrentNumbersNeverDuplicate() throws Exception {
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<List<Long>>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    List<Long> got = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        got.add(nextInNewTransaction(provider));
                    }
                    return got;
                });
            }
            Set<Long> all = new HashSet<>();
            for (Future<List<Long>> future : pool.invokeAll(tasks)) {
                all.addAll(future.get());
            }
            // 无重号：总数 = 线程数 × 每线程次数；无空洞：恰为 1..N
            assertThat(all).hasSize(threads * perThread);
            assertThat(all).contains(1L, (long) threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 重启不重号：新建 provider 实例（模拟进程重启）后取号延续，不回到 1 */
    @Test
    void numbersSurviveRestart() {
        long before = nextInNewTransaction(provider);

        // 模拟重启：丢弃旧实例，新实例只依赖数据库状态
        SequenceProvider restarted = new JdbcSequenceProvider(jdbc);
        long after = nextInNewTransaction(restarted);

        assertThat(after).isEqualTo(before + 1);
    }

    /** 复现生产事务语义：每次取号一个独立事务（REQUIRES_NEW），提交即占用 */
    private long nextInNewTransaction(SequenceProvider target) {
        Long value = txTemplate.execute(status -> target.next(scopeKey));
        assertThat(value).isNotNull();
        return value;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
