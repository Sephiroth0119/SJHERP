package com.sjherp.infra.persistence.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.inventory.InventoryPolicy;
import com.sjherp.domain.inventory.InventoryService;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.MovingWeightedAverageCalculator;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.transfer.InventoryPostingPort;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferLineInput;
import com.sjherp.domain.transfer.TransferQuery;
import com.sjherp.domain.transfer.TransferService;
import com.sjherp.infra.persistence.MySqlContainerTestBase;
import com.sjherp.infra.persistence.inventory.JdbcInventoryBalanceRepository;
import com.sjherp.infra.persistence.inventory.JdbcInventoryTransactionRepository;

/**
 * 调拨单整链真实 MySQL 集成测试（M3-T04 验收，X-2 模式）：
 * V12 迁移随基类全量 Flyway 执行（迁移可执行本身就是测试价值）；
 * 两仓期初 → 建单 → 审核 → 过账，断言：
 * <ul>
 *   <li>调出仓余额减少、调入仓余额增加（数量与金额）；</li>
 *   <li><b>金额守恒</b>：调出仓减少额 == 调入仓增加额（移动加权成本，调入用调出原值）；</li>
 *   <li>对账恒等式：每仓每商品 Σ流水 quantity = 余额数量、Σ流水 total_cost = 余额金额。</li>
 * </ul>
 *
 * <p>生产中事务由 app 层 TransferAppService / TransactionalInventoryService 代理提供；本测试用
 * TransactionTemplate 显式包裹每次过账（InventoryService.lockForUpdate 是 MANDATORY，必须在事务内），
 * 复现同样的「锁随外层事务释放、两腿同事务原子提交」语义。
 *
 * <p>默认不执行：@Tag("integration-db") 随基类被父 POM excludedGroups 排除（本机可能无 Docker）。
 */
class TransferIntegrationTest extends MySqlContainerTestBase {

    private static final String OPERATOR = "it-transfer";

    /** 仓库/商品 id 发号器（无外键约束，集成测试用即造 id 隔离各用例数据） */
    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    private InventoryService inventoryService;
    private JdbcTransferRepository transferRepository;
    private TransferService transferService;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        JdbcInventoryBalanceRepository balanceRepository = new JdbcInventoryBalanceRepository(jdbc);
        JdbcInventoryTransactionRepository transactionRepository =
                new JdbcInventoryTransactionRepository(jdbc);
        inventoryService = new InventoryService(balanceRepository, transactionRepository,
                new MovingWeightedAverageCalculator(), InventoryPolicy.defaults());
        transferRepository = new JdbcTransferRepository(jdbc);
        // 调拨过账端口直接转调领域 InventoryService（生产经 TransactionalInventoryService，事务在此由 txTemplate 提供）
        InventoryPostingPort postingPort = inventoryService::execute;
        transferService = new TransferService(transferRepository, postingPort, NoopPublisher.INSTANCE);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    @Test
    void 两仓调拨整链_余额变动且金额守恒且对账恒等式成立() {
        long whFrom = nextId();
        long whTo = nextId();
        long product = nextId();
        String suffix = uniqueSuffix();

        // 调出仓期初 100 @10.00（加权单价 10.000000）；调入仓期初 50 @8.00
        opening(whFrom, product, "100", "10.00", "OP-IT-F-" + suffix);
        opening(whTo, product, "50", "8.00", "OP-IT-T-" + suffix);

        InventoryBalanceView fromBefore = inventoryService.balanceOf(whFrom, product);
        InventoryBalanceView toBefore = inventoryService.balanceOf(whTo, product);
        assertThat(fromBefore.quantity()).isEqualByComparingTo("100");
        assertThat(fromBefore.costAmount()).isEqualByComparingTo("1000.00");
        assertThat(toBefore.quantity()).isEqualByComparingTo("50");
        assertThat(toBefore.costAmount()).isEqualByComparingTo("400.00");

        // 建单 → 审核 → 过账：调拨 70 个
        String docNo = "TR-IT-" + suffix;
        txTemplate.executeWithoutResult(status -> transferService.create(docNo, whFrom, whTo, "整链验收",
                List.of(new TransferLineInput(product, new BigDecimal("70"))), OPERATOR));
        txTemplate.executeWithoutResult(status -> transferService.approve(docNo, OPERATOR));
        TransferDocument completed = txTemplate.execute(status -> transferService.post(docNo, OPERATOR));
        assertThat(completed.getStatus()).isEqualTo(DocumentStatus.COMPLETED);

        InventoryBalanceView fromAfter = inventoryService.balanceOf(whFrom, product);
        InventoryBalanceView toAfter = inventoryService.balanceOf(whTo, product);

        // 调出仓数量减 70（剩 30）；调出按加权 10.000000，金额减 700.00（剩 300.00）
        assertThat(fromAfter.quantity()).isEqualByComparingTo("30");
        assertThat(fromAfter.costAmount()).isEqualByComparingTo("300.00");
        // 调入仓数量加 70（共 120）；调入用调出原值 700.00，金额 400.00 + 700.00 = 1100.00
        assertThat(toAfter.quantity()).isEqualByComparingTo("120");
        assertThat(toAfter.costAmount()).isEqualByComparingTo("1100.00");

        // 金额守恒：调出仓减少额 == 调入仓增加额（调拨不增减企业库存价值）
        BigDecimal fromDecrease = fromBefore.costAmount().subtract(fromAfter.costAmount());
        BigDecimal toIncrease = toAfter.costAmount().subtract(toBefore.costAmount());
        assertThat(fromDecrease).as("调出减少额 == 调入增加额（金额守恒）")
                .isEqualByComparingTo(toIncrease);
        assertThat(fromDecrease).isEqualByComparingTo("700.00");

        // 对账恒等式：每仓每商品 Σ流水 = 余额两列
        assertReconciliation(whFrom, product, fromAfter);
        assertReconciliation(whTo, product, toAfter);

        // 两腿流水各自落库（调出腿 :OUT 出库负数；调入腿 :IN 入库正数）
        BigDecimal outQty = jdbc.queryForObject(
                "SELECT quantity FROM inventory_transaction WHERE idempotency_key = ?",
                BigDecimal.class, "TRANSFER:" + docNo + ":1:OUT");
        BigDecimal inQty = jdbc.queryForObject(
                "SELECT quantity FROM inventory_transaction WHERE idempotency_key = ?",
                BigDecimal.class, "TRANSFER:" + docNo + ":1:IN");
        assertThat(outQty).isEqualByComparingTo("-70");
        assertThat(inQty).isEqualByComparingTo("70");
        // 调入腿成本（total_cost）== 调出腿成本绝对值（金额守恒的流水级证据）
        BigDecimal outTotal = jdbc.queryForObject(
                "SELECT total_cost FROM inventory_transaction WHERE idempotency_key = ?",
                BigDecimal.class, "TRANSFER:" + docNo + ":1:OUT");
        BigDecimal inTotal = jdbc.queryForObject(
                "SELECT total_cost FROM inventory_transaction WHERE idempotency_key = ?",
                BigDecimal.class, "TRANSFER:" + docNo + ":1:IN");
        assertThat(inTotal).isEqualByComparingTo(outTotal.negate());
        assertThat(inTotal).isEqualByComparingTo("700.00");
    }

    @Test
    void 调拨单按单据号查回与分页() {
        long whFrom = nextId();
        long whTo = nextId();
        long product = nextId();
        String docNo = "TR-IT-Q-" + uniqueSuffix();

        txTemplate.executeWithoutResult(status -> transferService.create(docNo, whFrom, whTo, "查回",
                List.of(new TransferLineInput(product, new BigDecimal("5"))), OPERATOR));

        TransferDocument found = transferRepository.findByDocNo(docNo).orElseThrow();
        assertThat(found.getFromWarehouseId()).isEqualTo(whFrom);
        assertThat(found.getToWarehouseId()).isEqualTo(whTo);
        assertThat(found.getLines()).hasSize(1);
        assertThat(found.getLines().get(0).getQuantity()).isEqualByComparingTo("5");

        // 按调出仓过滤分页命中
        PageResult<TransferDocument> page = transferRepository.search(
                new TransferQuery(whFrom, DocumentStatus.DRAFT, 1, 20));
        assertThat(page.items()).extracting(TransferDocument::getDocNo).contains(docNo);
    }

    // ---------------------------------------------------------------
    // 工具
    // ---------------------------------------------------------------

    private void opening(long warehouseId, long productId, String quantity, String unitCost, String docNo) {
        txTemplate.executeWithoutResult(status -> inventoryService.inbound(new InboundCommand(
                warehouseId, productId, InventoryTxnType.OPENING, new BigDecimal(quantity),
                new BigDecimal(unitCost), null, "OPENING", docNo, 1,
                "OPENING:" + docNo + ":1"), OPERATOR));
    }

    /** 对账恒等式：Σ流水 quantity = 余额数量、Σ流水 total_cost = 余额金额 */
    private void assertReconciliation(long warehouseId, long productId, InventoryBalanceView balance) {
        BigDecimal sumQty = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                BigDecimal.class, warehouseId, productId);
        BigDecimal sumTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_cost), 0) FROM inventory_transaction "
                        + "WHERE tenant_id = 0 AND warehouse_id = ? AND product_id = ?",
                BigDecimal.class, warehouseId, productId);
        assertThat(sumQty).as("Σ流水数量 = 余额数量（仓 " + warehouseId + "）")
                .isEqualByComparingTo(balance.quantity());
        assertThat(sumTotal).as("Σ流水金额 = 余额金额（仓 " + warehouseId + "）")
                .isEqualByComparingTo(balance.costAmount());
    }

    /** 无操作事件发布器（集成测试不验证审计落库链路，单据状态流转事件忽略） */
    private enum NoopPublisher implements DomainEventPublisher {
        INSTANCE;

        @Override
        public void publish(DomainEvent event) {
            // no-op
        }
    }
}
