package com.sjherp.app.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 事务感知审计写入器单测（D-8 幽灵审计修复，不依赖容器/数据库）：
 * 用 TransactionSynchronizationManager 的线程绑定 API 模拟「活动事务」环境，
 * 验证核心策略——有事务时注册 synchronization 延迟到 afterCommit 插入
 * （回滚则不插），无事务时立即插入；插入失败只计数不外抛。
 *
 * <p>真实事务管理器 + 真实 MySQL 的端到端验证见
 * {@code GhostAuditPreventionIntegrationTest}（@Tag integration-db）。
 */
class TransactionAwareAuditWriterTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditMetrics metrics = new AuditMetrics();
    private final TransactionAwareAuditWriter writer =
            new TransactionAwareAuditWriter(repository, metrics);

    @AfterEach
    void tearDown() {
        // 清理线程绑定的模拟事务状态，防止串到其他测试
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static AuditLogEntry entry() {
        return new AuditLogEntry(null, "tester", "customer.create", "customer",
                1L, "CUS-1", "名称=测试客户", null, Instant.now());
    }

    /** 模拟进入活动事务（等价于事务管理器 begin 后的线程状态） */
    private static void beginSimulatedTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @Test
    void 无活动事务时立即插入() {
        AuditLogEntry e = entry();
        writer.write(e);
        verify(repository).insert(e);
    }

    @Test
    void 有活动事务时注册synchronization且不立即插入_提交后才插入() {
        beginSimulatedTransaction();
        AuditLogEntry e = entry();

        writer.write(e);

        // 事务内：未插入，只注册了一个 synchronization
        verifyNoInteractions(repository);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size(), "应注册且仅注册一个 afterCommit synchronization");

        // 模拟事务管理器提交后回调 afterCommit → 此时才真正插入
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(repository).insert(e);
    }

    @Test
    void 有活动事务且业务回滚时审计不写_幽灵审计不可能出现() {
        beginSimulatedTransaction();
        writer.write(entry());

        // 模拟回滚：事务管理器只回调 afterCompletion(STATUS_ROLLED_BACK)，不回调 afterCommit
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        verifyNoInteractions(repository);
        assertEquals(0, metrics.failureCount(), "回滚不写审计不算失败");
    }

    @Test
    void afterCommit插入失败只计数不外抛() {
        doThrow(new RuntimeException("提交后瞬间断库")).when(repository).insert(any());
        beginSimulatedTransaction();
        writer.write(entry());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertDoesNotThrow(() -> synchronizations.forEach(TransactionSynchronization::afterCommit),
                "afterCommit 中审计失败绝不外抛（不得干扰提交后的清理流程）");
        assertEquals(1, metrics.failureCount(), "审计失败必须计数可发现");
    }

    @Test
    void 无事务路径插入失败只计数不外抛() {
        doThrow(new RuntimeException("数据库连不上")).when(repository).insert(any());
        assertDoesNotThrow(() -> writer.write(entry()));
        assertEquals(1, metrics.failureCount());
    }
}
