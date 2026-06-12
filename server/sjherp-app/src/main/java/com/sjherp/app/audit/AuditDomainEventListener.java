package com.sjherp.app.audit;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.domain.common.event.DocumentStatusChangedEvent;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.infra.persistence.audit.AuditLogEntry;

/**
 * 领域事件 → 审计日志监听器（M2-T07，为 M3 单据做准备）：
 * {@link DocumentStatusChangedEvent} 落 audit_log（action=document.status_changed，
 * target_code=单据号，摘要含前后状态）；其余事件类型暂只 DEBUG 记录（按需扩展）。
 *
 * <p>失败兜底同 AuditAspect：落库异常只 WARN + 计数，绝不外抛
 * （SyncDomainEventPublisher 侧还有一层隔离，双保险）。
 *
 * <p>落库经 {@link TransactionAwareAuditWriter}（D-8 幽灵审计修复）：同步分发发生在
 * 业务事务内时，审计延迟到事务提交后插入——单据事务回滚则状态流转审计不写。
 */
public class AuditDomainEventListener implements Consumer<DomainEvent> {

    private static final Logger log = LoggerFactory.getLogger(AuditDomainEventListener.class);

    private final TransactionAwareAuditWriter auditWriter;
    private final AuditMetrics metrics;

    public AuditDomainEventListener(TransactionAwareAuditWriter auditWriter, AuditMetrics metrics) {
        this.auditWriter = auditWriter;
        this.metrics = metrics;
    }

    @Override
    public void accept(DomainEvent event) {
        if (!(event instanceof DocumentStatusChangedEvent statusChanged)) {
            log.debug("暂不落审计的领域事件类型: {}", event);
            return;
        }
        try {
            auditWriter.write(new AuditLogEntry(null, statusChanged.getOperator(),
                    "document.status_changed", "document", null, statusChanged.getDocNo(),
                    "状态流转: " + statusChanged.getFromStatus() + " → " + statusChanged.getToStatus()
                            + "（事件 id=" + statusChanged.getEventId() + "）",
                    AuditContext.sessionId(), statusChanged.getOccurredAt()));
        } catch (RuntimeException e) {
            // 此处兜底的是「取材/注册」阶段的异常；插入阶段的异常由 writer 内部兜底
            metrics.recordFailure();
            log.warn("单据状态流转事件审计取材失败（docNo={}），业务不受影响但审计缺失，需尽快排查"
                            + "（累计失败 {} 次）",
                    statusChanged.getDocNo(), metrics.failureCount(), e);
        }
    }
}
