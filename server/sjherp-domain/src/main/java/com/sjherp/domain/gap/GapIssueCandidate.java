package com.sjherp.domain.gap;

import com.sjherp.domain.common.audit.AuditTarget;
import java.time.Instant;
import java.util.List;

public record GapIssueCandidate(
        long id,
        String idempotencyKey,
        String clusterKey,
        BusinessModule businessModule,
        GapSeverity severity,
        String title,
        List<String> scenarioSamples,
        String expectedBehavior,
        String missingCapability,
        List<String> sourceGapNos,
        GapIssueStatus status,
        Long issueNumber,
        String issueUrl,
        String reviewedBy,
        Instant reviewedAt,
        String failureType,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant sendingStartedAt) implements AuditTarget {

    public GapIssueCandidate {
        scenarioSamples = List.copyOf(scenarioSamples);
        sourceGapNos = List.copyOf(sourceGapNos);
    }

    @Override
    public Long auditTargetId() {
        return id;
    }

    @Override
    public String auditTargetCode() {
        return idempotencyKey;
    }

    @Override
    public String auditSummary() {
        return "candidate=" + idempotencyKey + ", status=" + status + ", issue=" + issueNumber;
    }
}
