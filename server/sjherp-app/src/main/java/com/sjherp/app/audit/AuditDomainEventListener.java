package com.sjherp.app.audit;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.domain.common.event.DocumentStatusChangedEvent;
import com.sjherp.domain.common.event.DomainEvent;
import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 领域事件 → 审计日志监听器（M2-T07，为 M3 单据做准备）：
 * {@link DocumentStatusChangedEvent} 落 audit_log（action=document.status_changed，
 * target_code=单据号，摘要含前后状态）；其余事件类型暂只 DEBUG 记录（按需扩展）。
 *
 * <p>失败兜底同 AuditAspect：落库异常只 WARN + 计数，绝不外抛
 * （SyncDomainEventPublisher 侧还有一层隔离，双保险）。
 */
public class AuditDomainEventListener implements Consumer<DomainEvent> {

    private static final Logger log = LoggerFactory.getLogger(AuditDomainEventListener.class);

    private final AuditLogRepository repository;
    private final AuditMetrics metrics;

    public AuditDomainEventListener(AuditLogRepository repository, AuditMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Override
    public void accept(DomainEvent event) {
        if (!(event instanceof DocumentStatusChangedEvent statusChanged)) {
            log.debug("暂不落审计的领域事件类型: {}", event);
            return;
        }
        try {
            repository.insert(new AuditLogEntry(null, statusChanged.getOperator(),
                    "document.status_changed", "document", null, statusChanged.getDocNo(),
                    "状态流转: " + statusChanged.getFromStatus() + " → " + statusChanged.getToStatus()
                            + "（事件 id=" + statusChanged.getEventId() + "）",
                    AuditContext.sessionId(), statusChanged.getOccurredAt()));
        } catch (RuntimeException e) {
            metrics.recordFailure();
            log.warn("单据状态流转事件审计落库失败（docNo={}），业务不受影响但审计缺失，需尽快排查"
                            + "（累计失败 {} 次）",
                    statusChanged.getDocNo(), metrics.failureCount(), e);
        }
    }
}
