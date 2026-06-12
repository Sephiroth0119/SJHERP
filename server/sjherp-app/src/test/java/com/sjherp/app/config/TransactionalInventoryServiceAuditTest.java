package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import com.sjherp.app.audit.AuditAspect;
import com.sjherp.app.audit.AuditMetrics;
import com.sjherp.app.audit.TransactionAwareAuditWriter;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.inventory.InventoryBalance;
import com.sjherp.domain.inventory.InventoryBalanceRepository;
import com.sjherp.domain.inventory.InventoryPolicy;
import com.sjherp.domain.inventory.InventoryService;
import com.sjherp.domain.inventory.InventoryTransaction;
import com.sjherp.domain.inventory.InventoryTransactionRepository;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.MovingWeightedAverageCalculator;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 库存审计路径单测（M3-T01b 审计结论的无 DB 验证，真库整链见
 * {@code InventoryPostingIntegrationTest}）：还原生产装配的调用链——
 * {@link TransactionalInventoryService}（无 @Audited 的事务薄包装）委托
 * <b>经真实 {@link AuditAspect} 代理的</b> {@link InventoryService}（@Audited
 * 在领域方法上）——验证每次过账审计<b>恰好一条</b>：
 * <ul>
 *   <li>包装类未标注 @Audited，切面不拦截它 → 不双记；</li>
 *   <li>领域 Service 注册为 Bean（此处用 AspectJProxyFactory 等价代理）→ 不漏记；</li>
 *   <li>业务方法抛异常（如库存不足）→ 写操作未发生，零审计。</li>
 * </ul>
 */
class TransactionalInventoryServiceAuditTest {

    private static final String OPERATOR = "tester";

    private final AuditLogRepository auditRepository = mock(AuditLogRepository.class);
    private TransactionalInventoryService service;

    @BeforeEach
    void setUp() {
        // 真实切面套在真实领域 Service 上（与容器内自动代理同一套匹配语义，
        // 模式同 AuditWriteCoverageTest）；仓储用内存 fake，无须数据库
        AspectJProxyFactory factory = new AspectJProxyFactory(new InventoryService(
                new InMemoryBalanceRepository(), new InMemoryTransactionRepository(),
                new MovingWeightedAverageCalculator(), InventoryPolicy.defaults()));
        factory.setProxyTargetClass(true);
        AuditMetrics metrics = new AuditMetrics();
        factory.addAspect(new AuditAspect(
                new TransactionAwareAuditWriter(auditRepository, metrics), metrics));
        // 生产同构：包装类持有的 delegate 是被审计代理后的 InventoryService
        service = new TransactionalInventoryService(factory.getProxy());
    }

    private static InboundCommand opening(String key) {
        return new InboundCommand(1L, 2L, InventoryTxnType.OPENING, new BigDecimal("100"),
                new BigDecimal("10.00"), null, "OPENING", "OP-202606-0001", 1, key);
    }

    @Test
    void 经包装类过账_审计恰好一条_不因包装层双记() {
        service.inbound(opening("OPENING:OP-202606-0001:1"), OPERATOR);

        // 核心断言：调用链穿过包装类 + 审计代理 + 领域方法，audit 仓储恰好被插入一次
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository, times(1)).insert(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("inventory.inbound");
        assertThat(entry.targetType()).isEqualTo("inventory");
        assertThat(entry.operator()).isEqualTo(OPERATOR);
        assertThat(entry.targetCode()).as("流水的业务编码 = 幂等键")
                .isEqualTo("OPENING:OP-202606-0001:1");
        assertThat(entry.summary()).contains("期初").contains("数量=100");
    }

    @Test
    void 出库与成本调整也各产生一条审计() {
        service.inbound(opening("OPENING:OP-202606-0001:1"), OPERATOR);
        service.outbound(new OutboundCommand(1L, 2L, InventoryTxnType.SALES_OUT,
                new BigDecimal("30"), "SALES_DELIVERY", "SD-202606-0001", 1,
                "SALES_DELIVERY:SD-202606-0001:1"), OPERATOR);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(AuditLogEntry::action)
                .containsExactly("inventory.inbound", "inventory.outbound");
    }

    @Test
    void 业务方法抛异常_写操作未发生_零审计() {
        assertThatThrownBy(() -> service.outbound(new OutboundCommand(1L, 2L,
                InventoryTxnType.SALES_OUT, new BigDecimal("1"), "SALES_DELIVERY",
                "SD-202606-0002", 1, "SALES_DELIVERY:SD-202606-0002:1"), OPERATOR))
                .isInstanceOf(InsufficientStockException.class);

        verify(auditRepository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    // ---------------------------------------------------------------
    // 内存 fake 仓储（无事务语义：本测试只验证审计路径，事务/锁语义见集成测试）
    // ---------------------------------------------------------------

    private static final class InMemoryBalanceRepository implements InventoryBalanceRepository {

        private final Map<String, InventoryBalance> rows = new HashMap<>();
        private long idSeq;

        private static String key(long warehouseId, long productId) {
            return warehouseId + ":" + productId;
        }

        @Override
        public InventoryBalance lockForUpdate(long warehouseId, long productId, String operator) {
            return rows.computeIfAbsent(key(warehouseId, productId), k -> {
                InventoryBalance zero = InventoryBalance.openZero(warehouseId, productId, operator);
                zero.assignId(++idSeq);
                return zero;
            });
        }

        @Override
        public void save(InventoryBalance balance) {
            if (balance.getId() == null) {
                balance.assignId(++idSeq);
            }
            rows.put(key(balance.getWarehouseId(), balance.getProductId()), balance);
        }

        @Override
        public Optional<InventoryBalance> find(long warehouseId, long productId) {
            return Optional.ofNullable(rows.get(key(warehouseId, productId)));
        }
    }

    private static final class InMemoryTransactionRepository implements InventoryTransactionRepository {

        private final List<InventoryTransaction> rows = new ArrayList<>();
        private long idSeq;

        @Override
        public void save(InventoryTransaction transaction) {
            transaction.assignId(++idSeq);
            rows.add(transaction);
        }

        @Override
        public Optional<InventoryTransaction> findByIdempotencyKey(String idempotencyKey) {
            return rows.stream()
                    .filter(txn -> txn.getIdempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<InventoryTransaction> findLatestWithUnitCost(long warehouseId, long productId) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                InventoryTransaction txn = rows.get(i);
                if (txn.getWarehouseId() == warehouseId && txn.getProductId() == productId
                        && txn.getUnitCost() != null) {
                    return Optional.of(txn);
                }
            }
            return Optional.empty();
        }
    }
}
