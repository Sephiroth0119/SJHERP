package com.sjherp.infra.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sjherp.domain.inventory.InventoryBalance;
import com.sjherp.domain.inventory.InventoryTransaction;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * 库存两仓储真实 MySQL 集成测试（M3-T01b 验收，X-2 模式）：
 * V10 迁移随基类全量 Flyway 执行（迁移可执行本身就是测试价值）；
 * 余额仓储 lockForUpdate 零行初始化 / save 往返，流水仓储 insert 回填 id /
 * 幂等键唯一约束 / findLatestWithUnitCost 口径。
 *
 * <p>生产中事务由 app 层 TransactionalInventoryService 代理提供；本测试用
 * TransactionTemplate 显式包裹 FOR UPDATE 路径，复现同样的「锁随外层事务释放」语义。
 *
 * <p>默认不执行：@Tag("integration-db") 随基类被父 POM excludedGroups 排除（本机可能无 Docker）。
 */
class InventoryRepositoriesIntegrationTest extends MySqlContainerTestBase {

    private static final String OPERATOR = "it-admin";

    /** 仓库/商品 id 发号器（无外键约束，集成测试用即造 id 隔离各用例数据） */
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private JdbcInventoryBalanceRepository balanceRepository;
    private JdbcInventoryTransactionRepository transactionRepository;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        balanceRepository = new JdbcInventoryBalanceRepository(jdbc);
        transactionRepository = new JdbcInventoryTransactionRepository(jdbc);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // ---------------------------------------------------------------
    // 余额仓储
    // ---------------------------------------------------------------

    @Test
    void lockForUpdate行不存在时插入初始零行并回填id() {
        long warehouseId = nextId();
        long productId = nextId();

        InventoryBalance locked = txTemplate.execute(status ->
                balanceRepository.lockForUpdate(warehouseId, productId, OPERATOR));

        assertThat(locked).isNotNull();
        assertThat(locked.getId()).as("零行落库后回填自增 id").isNotNull();
        assertThat(locked.getQuantity()).isEqualByComparingTo("0");
        assertThat(locked.getCostAmount()).isEqualByComparingTo("0");
        assertThat(locked.getUpdatedBy()).isEqualTo(OPERATOR);

        // 事务提交后零行持久存在（tenant_id / batch_id 恒 0 由 infra 落列）
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_balance "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ? AND batch_id = 0",
                Long.class, warehouseId, productId);
        assertThat(count).isEqualTo(1);

        // 再次锁定：走 SELECT ... FOR UPDATE 路径，返回同一行（不重复插入）
        InventoryBalance again = txTemplate.execute(status ->
                balanceRepository.lockForUpdate(warehouseId, productId, OPERATOR));
        assertThat(again).isNotNull();
        assertThat(again.getId()).isEqualTo(locked.getId());
    }

    @Test
    void 零行已被并发预占时撞唯一键退回锁行路径() {
        long warehouseId = nextId();
        long productId = nextId();

        // 直插一行模拟并发已提交的零行（绕过仓储仅限测试构造数据；唯一键维度同仓储写入）
        jdbc.update("INSERT INTO inventory_balance (tenant_id, warehouse_id, product_id, batch_id, "
                        + "quantity, cost_amount, updated_by, updated_at) "
                        + "VALUES (0, ?, ?, 0, 7.000000, 70.00, 'preempt', UTC_TIMESTAMP(6))",
                warehouseId, productId);

        InventoryBalance locked = txTemplate.execute(status ->
                balanceRepository.lockForUpdate(warehouseId, productId, OPERATOR));

        assertThat(locked).isNotNull();
        assertThat(locked.getQuantity()).isEqualByComparingTo("7");
        assertThat(locked.getCostAmount()).isEqualByComparingTo("70.00");
    }

    @Test
    void 余额save更新与find只读往返() {
        long warehouseId = nextId();
        long productId = nextId();
        InventoryBalance created = txTemplate.execute(status ->
                balanceRepository.lockForUpdate(warehouseId, productId, OPERATOR));
        assertThat(created).isNotNull();

        // 模拟过账后的余额（restore 重建携带已分配 id → save 走 UPDATE 路径）
        Instant updatedAt = Instant.now();
        InventoryBalance posted = InventoryBalance.restore(created.getId(), warehouseId, productId,
                new BigDecimal("150.000000"), new BigDecimal("1625.00"), "it-poster", updatedAt);
        txTemplate.executeWithoutResult(status -> balanceRepository.save(posted));

        InventoryBalance found = balanceRepository.find(warehouseId, productId).orElseThrow();
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getQuantity()).isEqualByComparingTo("150");
        assertThat(found.getCostAmount()).isEqualByComparingTo("1625.00");
        assertThat(found.getUpdatedBy()).isEqualTo("it-poster");
        // DATETIME(6) 微秒精度（UTC 读写往返）
        assertThat(found.getUpdatedAt()).isCloseTo(updatedAt, within(1, ChronoUnit.MILLIS));
    }

    @Test
    void find无行时返回empty() {
        assertThat(balanceRepository.find(nextId(), nextId())).isEmpty();
    }

    // ---------------------------------------------------------------
    // 流水仓储
    // ---------------------------------------------------------------

    @Test
    void 流水insert回填id并按幂等键读回全部列() {
        long warehouseId = nextId();
        long productId = nextId();
        String key = "PURCHASE_RECEIPT:PR-IT-" + uniqueSuffix() + ":1";

        InventoryTransaction transaction = new InventoryTransaction(warehouseId, productId,
                InventoryTxnType.PURCHASE_IN, new BigDecimal("50.000000"),
                new BigDecimal("12.500000"), new BigDecimal("625.00"),
                new BigDecimal("150.000000"), new BigDecimal("1625.00"),
                "PURCHASE_RECEIPT", "PR-IT-0001", 1, key, OPERATOR);
        transactionRepository.save(transaction);

        assertThat(transaction.getId()).as("落库后回填自增 id").isNotNull();

        InventoryTransaction found = transactionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(found.getId()).isEqualTo(transaction.getId());
        assertThat(found.getWarehouseId()).isEqualTo(warehouseId);
        assertThat(found.getProductId()).isEqualTo(productId);
        assertThat(found.getTxnType()).isEqualTo(InventoryTxnType.PURCHASE_IN);
        assertThat(found.getQuantity()).isEqualByComparingTo("50");
        assertThat(found.getUnitCost()).isEqualByComparingTo("12.5");
        assertThat(found.getTotalCost()).isEqualByComparingTo("625.00");
        assertThat(found.getBalanceQuantityAfter()).isEqualByComparingTo("150");
        assertThat(found.getBalanceAmountAfter()).isEqualByComparingTo("1625.00");
        assertThat(found.getSrcDocType()).isEqualTo("PURCHASE_RECEIPT");
        assertThat(found.getSrcDocNo()).isEqualTo("PR-IT-0001");
        assertThat(found.getSrcLineNo()).isEqualTo(1);
        assertThat(found.getIdempotencyKey()).isEqualTo(key);
        assertThat(found.getOperator()).isEqualTo(OPERATOR);
        assertThat(found.getCreatedAt()).isCloseTo(transaction.getCreatedAt(),
                within(1, ChronoUnit.MILLIS));
    }

    @Test
    void 可空列往返_成本调整流水单价与行号为null() {
        long warehouseId = nextId();
        long productId = nextId();
        String key = "COST_ADJUST:CA-IT-" + uniqueSuffix();

        InventoryTransaction adjust = new InventoryTransaction(warehouseId, productId,
                InventoryTxnType.COST_ADJUST, new BigDecimal("0.000000"),
                null, new BigDecimal("12.62"),
                new BigDecimal("145.000000"), new BigDecimal("1550.00"),
                "COST_ADJUST", "CA-IT-0001", null, key, OPERATOR);
        transactionRepository.save(adjust);

        InventoryTransaction found = transactionRepository.findByIdempotencyKey(key).orElseThrow();
        assertThat(found.getUnitCost()).as("成本调整流水单价为 NULL").isNull();
        assertThat(found.getSrcLineNo()).as("src_line_no 可空且不被误读为 0").isNull();
        assertThat(found.getTotalCost()).isEqualByComparingTo("12.62");
    }

    @Test
    void 幂等键撞数据库唯一键抛DuplicateKeyException() {
        long warehouseId = nextId();
        long productId = nextId();
        String key = "OPENING:OP-IT-" + uniqueSuffix() + ":1";

        transactionRepository.save(opening(warehouseId, productId, key));

        // 唯一键 uk_inventory_txn_idempotency 兜底：同键再插必被数据库拒绝
        assertThatThrownBy(() -> transactionRepository.save(opening(warehouseId, productId, key)))
                .isInstanceOf(DuplicateKeyException.class);

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_transaction WHERE idempotency_key = ?", Long.class, key);
        assertThat(count).as("撞键后不残留重复流水").isEqualTo(1);
    }

    @Test
    void findLatestWithUnitCost取最近一笔且跳过单价为null的流水() {
        long warehouseId = nextId();
        long productId = nextId();
        String suffix = uniqueSuffix();

        // 依次落三笔：入库 @10 → 入库 @12 → 成本调整（unit_cost=NULL，id 最大）
        transactionRepository.save(new InventoryTransaction(warehouseId, productId,
                InventoryTxnType.OPENING, new BigDecimal("10.000000"),
                new BigDecimal("10.000000"), new BigDecimal("100.00"),
                new BigDecimal("10.000000"), new BigDecimal("100.00"),
                "OPENING", "OP-IT-L1", 1, "L1:" + suffix, OPERATOR));
        transactionRepository.save(new InventoryTransaction(warehouseId, productId,
                InventoryTxnType.PURCHASE_IN, new BigDecimal("10.000000"),
                new BigDecimal("12.000000"), new BigDecimal("120.00"),
                new BigDecimal("20.000000"), new BigDecimal("220.00"),
                "PURCHASE_RECEIPT", "PR-IT-L2", 1, "L2:" + suffix, OPERATOR));
        transactionRepository.save(new InventoryTransaction(warehouseId, productId,
                InventoryTxnType.COST_ADJUST, new BigDecimal("0.000000"),
                null, new BigDecimal("5.00"),
                new BigDecimal("20.000000"), new BigDecimal("225.00"),
                "COST_ADJUST", "CA-IT-L3", null, "L3:" + suffix, OPERATOR));

        InventoryTransaction latest = transactionRepository
                .findLatestWithUnitCost(warehouseId, productId).orElseThrow();
        assertThat(latest.getUnitCost()).as("跳过 NULL 单价的成本调整，取 @12 入库")
                .isEqualByComparingTo("12");
        assertThat(latest.getTxnType()).isEqualTo(InventoryTxnType.PURCHASE_IN);

        // 别的仓/别的商品互不可见
        assertThat(transactionRepository.findLatestWithUnitCost(warehouseId, nextId())).isEmpty();
    }

    private static InventoryTransaction opening(long warehouseId, long productId, String key) {
        return new InventoryTransaction(warehouseId, productId, InventoryTxnType.OPENING,
                new BigDecimal("100.000000"), new BigDecimal("10.000000"), new BigDecimal("1000.00"),
                new BigDecimal("100.000000"), new BigDecimal("1000.00"),
                "OPENING", "OP-IT-0001", 1, key, OPERATOR);
    }
}
