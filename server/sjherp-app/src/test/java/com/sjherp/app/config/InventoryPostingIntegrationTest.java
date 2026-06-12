package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.domain.inventory.CostAdjustCommand;
import com.sjherp.domain.inventory.IdempotencyConflictException;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.inventory.InventoryService;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 库存过账整链集成测试（M3-T01b 验收核心，Testcontainers 真实 MySQL）：
 * 用<b>生产同套装配</b>（@Import 真实 {@link AuditConfig} + {@link InventoryInfraConfig}，
 * 仅基础设施 Bean——数据源/事务管理器——为测试提供）跑通：
 * <ul>
 *   <li>教科书对账案例（拆解 docs/M3拆解-库存与成本.md §2）十步真库整链，
 *       每步断言流水数字与余额快照，终态对账 SQL 恒等式
 *       Σ流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额；</li>
 *   <li><b>审计路径结论</b>：@Audited 在领域方法上、调用方经
 *       {@link TransactionalInventoryService}（无 @Audited）委托被审计代理的
 *       InventoryService Bean——每次过账审计<b>恰好一条</b>（不漏记、不双记）；</li>
 *   <li>幂等：同键同参返回首次结果不追加流水；同键不同参抛
 *       {@link IdempotencyConflictException}；</li>
 *   <li>外层事务回滚：无幽灵审计、无流水残留、无余额残留（D-8 修复在库存路径回归）；</li>
 *   <li>绕过事务包装直接调领域 Service Bean → lockForUpdate（MANDATORY）fail-fast。</li>
 * </ul>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无
 * Docker）。CI 的 backend-integration-db job 显式运行：
 * <pre>mvn test -pl sjherp-infra,sjherp-app -Dgroups=integration-db -DexcludedGroups=none</pre>
 */
@Tag("integration-db")
class InventoryPostingIntegrationTest {

    /** MySQL 8.4（与 sjherp-infra 的 MySqlContainerTestBase 同版本约定） */
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    /** 仓库/商品 id 发号器（两表无外键约束，集成测试即造 id 隔离各用例数据） */
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static TransactionalInventoryService inventoryService;
    private static InventoryService domainService;

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
        txTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        inventoryService = context.getBean(TransactionalInventoryService.class);
        domainService = context.getBean(InventoryService.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
        // 容器由 Testcontainers Ryuk 自动回收
    }

    /**
     * 与生产同套装配：审计与库存两个真实 @Configuration 原样 @Import
     * （Boot 容器里它们也是这么生效的），测试只补数据源/JdbcTemplate/事务管理器
     * 三个基础设施 Bean（生产中由 Boot 自动配置提供）。
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

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static InboundCommand inbound(long warehouseId, long productId, InventoryTxnType type,
                                          String quantity, String unitCost, String key) {
        return new InboundCommand(warehouseId, productId, type, new BigDecimal(quantity),
                unitCost == null ? null : new BigDecimal(unitCost), null,
                "IT_CASE", "DOC-" + key, 1, key);
    }

    private static OutboundCommand outbound(long warehouseId, long productId, InventoryTxnType type,
                                            String quantity, String key) {
        return new OutboundCommand(warehouseId, productId, type, new BigDecimal(quantity),
                "IT_CASE", "DOC-" + key, 1, key);
    }

    private static CostAdjustCommand adjust(long warehouseId, long productId, String amount,
                                            String key) {
        return new CostAdjustCommand(warehouseId, productId, new BigDecimal(amount),
                "IT_CASE", "DOC-" + key, 1, key);
    }

    /** 单步断言：流水带符号数量/单价快照/带符号金额 + 过账后余额快照（§2 表逐行核对） */
    private static void assertStep(StockMovementResult result, String quantity, String unitCost,
                                   String totalCost, String balanceQuantity, String balanceAmount) {
        assertThat(result.quantity()).isEqualByComparingTo(quantity);
        if (unitCost == null) {
            assertThat(result.unitCost()).isNull();
        } else {
            assertThat(result.unitCost()).isEqualByComparingTo(unitCost);
        }
        assertThat(result.totalCost()).isEqualByComparingTo(totalCost);
        assertThat(result.balanceQuantityAfter()).isEqualByComparingTo(balanceQuantity);
        assertThat(result.balanceAmountAfter()).isEqualByComparingTo(balanceAmount);
    }

    private long transactionCount(long warehouseId, long productId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                Long.class, warehouseId, productId);
        return count == null ? -1 : count;
    }

    private long balanceRowCount(long warehouseId, long productId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                Long.class, warehouseId, productId);
        return count == null ? -1 : count;
    }

    /** 按幂等键（= 审计 target_code）统计库存审计记录数 */
    private long auditCount(String idempotencyKey) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log "
                        + "WHERE target_type = 'inventory' AND target_code = ?",
                Long.class, idempotencyKey);
        return count == null ? -1 : count;
    }

    // ---------------------------------------------------------------
    // 教科书案例 §2 十步整链（真实装配真库）
    // ---------------------------------------------------------------

    @Test
    void 教科书案例十步真库整链_逐步数字_对账恒等式_审计恰好一条() {
        long warehouseId = nextId();
        long productId = nextId();
        String run = "CASE:" + uniqueSuffix() + ":";

        // 1. 期初 100 @10.00
        assertStep(inventoryService.inbound(inbound(warehouseId, productId,
                        InventoryTxnType.OPENING, "100", "10.00", run + 1), OPERATOR),
                "100", "10.000000", "1000.00", "100", "1000.00");
        // 2. 采购入库 50 @12.50
        assertStep(inventoryService.inbound(inbound(warehouseId, productId,
                        InventoryTxnType.PURCHASE_IN, "50", "12.50", run + 2), OPERATOR),
                "50", "12.500000", "625.00", "150", "1625.00");
        // 3. 采购入库 30 @11.20
        assertStep(inventoryService.inbound(inbound(warehouseId, productId,
                        InventoryTxnType.PURCHASE_IN, "30", "11.20", run + 3), OPERATOR),
                "30", "11.200000", "336.00", "180", "1961.00");
        // 4. 销售出库 70：unit_cost = 1961.00/180 = 10.894444，total = 762.61108 → 762.61
        StockMovementResult step4 = inventoryService.outbound(outbound(warehouseId, productId,
                InventoryTxnType.SALES_OUT, "70", run + 4), OPERATOR);
        assertStep(step4, "-70", "10.894444", "-762.61", "110", "1198.39");
        // 销售出库单由此取 COGS（totalCost.negate() 即正数成本）
        assertThat(step4.totalCost().negate()).isEqualByComparingTo("762.61");
        // 5. 采购入库 40 @9.80
        assertStep(inventoryService.inbound(inbound(warehouseId, productId,
                        InventoryTxnType.PURCHASE_IN, "40", "9.80", run + 5), OPERATOR),
                "40", "9.800000", "392.00", "150", "1590.39");
        // 6. 盘亏 5：unit_cost = 1590.39/150 = 10.602600（整除验证点），total = 53.013 → 53.01
        assertStep(inventoryService.outbound(outbound(warehouseId, productId,
                        InventoryTxnType.COUNT_LOSS, "5", run + 6), OPERATOR),
                "-5", "10.602600", "-53.01", "145", "1537.38");
        // 7. 成本调整 +12.62（运费入成本）：数量不变只调金额，unit_cost 为 NULL
        assertStep(inventoryService.adjustCost(adjust(warehouseId, productId, "12.62", run + 7),
                        OPERATOR),
                "0", null, "12.62", "145", "1550.00");
        // 8. 销售出库 100：unit_cost = 1550.00/145 → 10.689655，total = 1068.9655 → 1068.97（进位验证点）
        assertStep(inventoryService.outbound(outbound(warehouseId, productId,
                        InventoryTxnType.SALES_OUT, "100", run + 8), OPERATOR),
                "-100", "10.689655", "-1068.97", "45", "481.03");
        // 9. 销售出库 45（出空清零）：total 直接取出库前结存金额 481.03，余额归 (0, 0.00)
        assertStep(inventoryService.outbound(outbound(warehouseId, productId,
                        InventoryTxnType.SALES_OUT, "45", run + 9), OPERATOR),
                "-45", "10.689556", "-481.03", "0", "0.00");
        // 出空后超量出库被拒（默认禁负库存），且余额/流水无任何变化
        assertThatThrownBy(() -> inventoryService.outbound(outbound(warehouseId, productId,
                InventoryTxnType.SALES_OUT, "1", run + "X"), OPERATOR))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(transactionCount(warehouseId, productId)).isEqualTo(9);
        // 10. 零库存后采购入库 20 @8.00：单价自然 = 本次入库价
        assertStep(inventoryService.inbound(inbound(warehouseId, productId,
                        InventoryTxnType.PURCHASE_IN, "20", "8.00", run + 10), OPERATOR),
                "20", "8.000000", "160.00", "20", "160.00");

        // 只读视图与真源一致（派生单价 160.00/20 = 8.000000）
        var view = inventoryService.balanceOf(warehouseId, productId);
        assertThat(view.quantity()).isEqualByComparingTo("20");
        assertThat(view.costAmount()).isEqualByComparingTo("160.00");
        assertThat(view.derivedUnitCost()).isEqualByComparingTo("8");

        // 对账 SQL 恒等式（拆解 §1.2 带符号设计的核心收益，检查 Agent M6-T06 同口径）：
        // Σ流水 quantity = 余额数量 且 Σ流水 total_cost = 余额金额
        var sums = jdbc.queryForMap("SELECT SUM(quantity) AS qty_sum, SUM(total_cost) AS cost_sum "
                        + "FROM inventory_transaction WHERE tenant_id = 0 AND warehouse_id = ? "
                        + "AND product_id = ?", warehouseId, productId);
        var balance = jdbc.queryForMap("SELECT quantity, cost_amount FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                warehouseId, productId);
        assertThat((BigDecimal) sums.get("qty_sum"))
                .as("Σ流水数量 = 余额数量 = 20")
                .isEqualByComparingTo((BigDecimal) balance.get("quantity"))
                .isEqualByComparingTo("20");
        assertThat((BigDecimal) sums.get("cost_sum"))
                .as("Σ流水金额 = 余额金额 = 160.00")
                .isEqualByComparingTo((BigDecimal) balance.get("cost_amount"))
                .isEqualByComparingTo("160.00");
        assertThat(transactionCount(warehouseId, productId)).as("十步十笔流水，被拒出库无残留")
                .isEqualTo(10);

        // 审计路径结论（任务核心验证）：@Audited 在领域方法上，TransactionalInventoryService
        // 包装类无 @Audited——每次过账审计恰好一条（包装层不双记，领域层不漏记）
        for (int step = 1; step <= 10; step++) {
            assertThat(auditCount(run + step)).as("第 %d 步过账审计恰好一条", step).isEqualTo(1);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log "
                        + "WHERE target_type = 'inventory' AND target_code LIKE ?",
                Long.class, run + "%");
        assertThat(total).as("整链审计总数 = 过账次数（被拒的第 11 次出库不记审计）").isEqualTo(10);
        // 审计动作与操作人抽查（操作人经 operator 参数原样落库）
        var auditRow = jdbc.queryForMap("SELECT action, operator FROM audit_log "
                + "WHERE target_type = 'inventory' AND target_code = ?", run + 4);
        assertThat(auditRow.get("action")).isEqualTo("inventory.outbound");
        assertThat(auditRow.get("operator")).isEqualTo(OPERATOR);
    }

    // ---------------------------------------------------------------
    // 幂等（拆解 §1.3）
    // ---------------------------------------------------------------

    @Test
    void 幂等重试_同键同参返回首次结果且不追加流水() {
        long warehouseId = nextId();
        long productId = nextId();
        String key = "IDEM:" + uniqueSuffix() + ":1";

        InboundCommand command = inbound(warehouseId, productId,
                InventoryTxnType.PURCHASE_IN, "10", "5.00", key);
        StockMovementResult first = inventoryService.inbound(command, OPERATOR);
        StockMovementResult replay = inventoryService.inbound(command, OPERATOR);

        assertThat(replay.transactionId()).as("重试返回首次流水（真幂等）")
                .isEqualTo(first.transactionId());
        assertThat(replay.balanceAmountAfter()).isEqualByComparingTo("50.00");
        assertThat(transactionCount(warehouseId, productId)).as("重试不追加流水").isEqualTo(1);
        // 余额未被二次累加
        assertThat(inventoryService.balanceOf(warehouseId, productId).quantity())
                .isEqualByComparingTo("10");
    }

    @Test
    void 幂等冲突_同键不同参抛异常且无任何写入() {
        long warehouseId = nextId();
        long productId = nextId();
        String key = "IDEM:" + uniqueSuffix() + ":2";

        inventoryService.inbound(inbound(warehouseId, productId,
                InventoryTxnType.PURCHASE_IN, "10", "5.00", key), OPERATOR);

        // 同键、数量不同 → 拒绝（防键误用静默吞单）
        assertThatThrownBy(() -> inventoryService.inbound(inbound(warehouseId, productId,
                InventoryTxnType.PURCHASE_IN, "99", "5.00", key), OPERATOR))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(transactionCount(warehouseId, productId)).isEqualTo(1);
        assertThat(inventoryService.balanceOf(warehouseId, productId).quantity())
                .isEqualByComparingTo("10");
    }

    // ---------------------------------------------------------------
    // 外层事务回滚（D-8 在库存路径的回归）
    // ---------------------------------------------------------------

    @Test
    void 外层事务回滚_无幽灵审计_无流水残留_无余额残留() {
        long warehouseId = nextId();
        long productId = nextId();
        String key = "RB:" + uniqueSuffix() + ":1";

        // 模拟 M3 单据形态：更大的外层事务包住过账（@Transactional REQUIRED 加入外层），最终回滚
        txTemplate.executeWithoutResult(status -> {
            StockMovementResult result = inventoryService.inbound(inbound(warehouseId, productId,
                    InventoryTxnType.OPENING, "100", "10.00", key), OPERATOR);
            assertThat(result.balanceQuantityAfter()).isEqualByComparingTo("100");
            // 事务内：审计延迟到 afterCommit，此刻必然查不到
            assertThat(auditCount(key)).as("事务内审计尚未写入").isZero();
            status.setRollbackOnly();
        });

        assertThat(transactionCount(warehouseId, productId)).as("回滚后无流水残留").isZero();
        assertThat(balanceRowCount(warehouseId, productId)).as("回滚后初始零行一并回滚").isZero();
        // D-8 核心断言（库存路径）：回滚后零审计——幽灵审计在结构上不可能出现
        assertThat(auditCount(key)).as("回滚后无幽灵审计").isZero();
    }

    // ---------------------------------------------------------------
    // 事务边界防误用
    // ---------------------------------------------------------------

    @Test
    void 绕过事务包装直接调领域Service_无外层事务被MANDATORY拒绝() {
        long warehouseId = nextId();
        long productId = nextId();
        String key = "BYPASS:" + uniqueSuffix() + ":1";

        // 设计约束（InventoryInfraConfig javadoc）：调用方只准注入 TransactionalInventoryService；
        // 直接调 InventoryService Bean 时 lockForUpdate（Propagation.MANDATORY）fail-fast
        assertThatThrownBy(() -> domainService.inbound(inbound(warehouseId, productId,
                InventoryTxnType.OPENING, "1", "1.00", key), OPERATOR))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(transactionCount(warehouseId, productId)).isZero();
        assertThat(balanceRowCount(warehouseId, productId)).isZero();
        assertThat(auditCount(key)).as("业务失败不记审计").isZero();
    }
}
