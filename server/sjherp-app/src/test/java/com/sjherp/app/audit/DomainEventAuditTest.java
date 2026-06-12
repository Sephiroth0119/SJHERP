package com.sjherp.app.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.app.event.SyncDomainEventPublisher;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.event.DocumentStatusChangedEvent;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.infra.persistence.audit.AuditLogEntry;
import com.sjherp.infra.persistence.audit.AuditLogRepository;

/**
 * 领域事件接线 + 审计落库测试（M2-T07，还 M2-T01 待办）：
 * DocumentStatusChangedEvent 经 SyncDomainEventPublisher 同步分发到
 * AuditDomainEventListener 并写 audit_log（action=document.status_changed，为 M3 单据做准备）；
 * 落库失败不外抛（计数器可见）。
 */
class DomainEventAuditTest {

    private final AuditLogRepository auditRepository = mock(AuditLogRepository.class);
    private final AuditMetrics metrics = new AuditMetrics();
    private final DomainEventPublisher publisher = new SyncDomainEventPublisher(
            List.of(new AuditDomainEventListener(auditRepository, metrics)));

    @Test
    void 单据状态流转事件写审计记录() {
        publisher.publish(new DocumentStatusChangedEvent("PO-202606-0001",
                DocumentStatus.DRAFT, DocumentStatus.APPROVED, "admin"));

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditRepository).insert(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertEquals("document.status_changed", entry.action());
        assertEquals("document", entry.targetType());
        assertEquals("PO-202606-0001", entry.targetCode());
        assertEquals("admin", entry.operator());
        assertNull(entry.targetId(), "单据以单据号定位，target_id 为空");
        assertTrue(entry.summary().contains("DRAFT") && entry.summary().contains("APPROVED"),
                "摘要应含前后状态: " + entry.summary());
    }

    @Test
    void 审计落库失败不反噬事件发布方_且计数可见() {
        doThrow(new RuntimeException("数据库连不上")).when(auditRepository).insert(any());

        assertDoesNotThrow(() -> publisher.publish(new DocumentStatusChangedEvent("PO-202606-0002",
                DocumentStatus.APPROVED, DocumentStatus.EXECUTING, "admin")));
        assertEquals(1, metrics.failureCount());
    }

    @Test
    void 非单据状态事件暂不落审计() {
        publisher.publish(new com.sjherp.domain.common.event.DomainEvent("AGG-1") {
        });
        verifyNoInteractions(auditRepository);
    }
}
