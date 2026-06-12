package com.sjherp.app.audit;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 事务感知的审计写入器（还技术债 D-8：幽灵审计修复）。
 *
 * <p><b>问题背景</b>：审计插入此前一律走 REQUIRES_NEW 独立事务立即提交。当 @Audited
 * 方法被外层跨表业务事务（M3 单据服务）包住时，外层回滚而审计已提交，产生
 * 「有审计无业务」的幽灵记录（详见 docs/审计日志.md）。
 *
 * <p><b>写入策略（本类的全部职责）</b>：
 * <ul>
 *   <li>调用线程存在<b>活动事务</b>：经 {@link TransactionSynchronizationManager}
 *       注册 afterCommit 回调，业务事务<b>提交后</b>才插入审计——业务回滚则回调
 *       不触发，审计自然不写，幽灵审计在结构上不可能出现；</li>
 *   <li><b>无活动事务</b>（现有档案路径：领域 Service 无事务、事务在 Jdbc 仓储
 *       方法级，业务先提交切面后插审计）：立即插入，行为与修复前完全一致。</li>
 * </ul>
 *
 * <p><b>失败哲学不变</b>（与 invocation listener 同源）：插入的任何异常只 WARN +
 * {@link AuditMetrics} 计数，绝不阻塞/反噬业务。afterCommit 路径存在「业务已提交、
 * 审计插入失败」的极小窗口（如提交后瞬间断库），与修复前 REQUIRES_NEW 的失败窗口
 * 等价，由 WARN + 计数器兜底发现。
 *
 * <p>注：{@code JdbcAuditLogRepository.insert} 保留 REQUIRES_NEW——afterCommit
 * 回调中原连接虽已提交但事务资源仍绑定线程，Spring 约定此处的数据访问应开新事务；
 * 无事务路径下它就是原来的独立事务语义，两条路径都正确。
 */
public class TransactionAwareAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(TransactionAwareAuditWriter.class);

    private final AuditLogRepository repository;
    private final AuditMetrics metrics;

    public TransactionAwareAuditWriter(AuditLogRepository repository, AuditMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /**
     * 写入一条审计记录（事务感知）：有活动事务则延迟到业务事务提交后（afterCommit），
     * 无活动事务则立即插入。插入失败只 WARN + 计数，不外抛。
     */
    public void write(AuditLogEntry entry) {
        Objects.requireNonNull(entry, "entry 不能为空");
        // isSynchronizationActive 是 registerSynchronization 的前置条件；
        // isActualTransactionActive 排除「同步活动但无真实事务」（如 NOT_SUPPORTED）的边缘场景
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 仅在业务事务成功提交后回调；回滚时本方法不会被调用 → 审计不写
                    insertQuietly(entry);
                }
            });
        } else {
            insertQuietly(entry);
        }
    }

    /** 实际落库：任何异常只 WARN + 计数（审计失败绝不阻塞业务） */
    private void insertQuietly(AuditLogEntry entry) {
        try {
            repository.insert(entry);
        } catch (RuntimeException e) {
            metrics.recordFailure();
            log.warn("审计日志写入失败（action={}, target={}/{}），业务不受影响但审计缺失，"
                            + "需尽快排查（累计失败 {} 次）",
                    entry.action(), entry.targetType(), entry.targetCode(),
                    metrics.failureCount(), e);
        }
    }
}
