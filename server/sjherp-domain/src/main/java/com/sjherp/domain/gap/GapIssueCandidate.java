package com.sjherp.domain.gap;

import java.util.List;

import com.sjherp.domain.common.audit.AuditTarget;

public record GapIssueCandidate(long id, String idempotencyKey, String clusterKey,
        BusinessModule businessModule, GapSeverity severity, String title,
        List<String> scenarioSamples, String expectedBehavior, String missingCapability,
        List<String> sourceGapNos, GapIssueStatus status, Long issueNumber, String issueUrl,
        String reviewedBy, java.time.Instant reviewedAt, String failureType, int attemptCount,
        java.time.Instant createdAt, java.time.Instant updatedAt, java.time.Instant sendingStartedAt) implements AuditTarget {
    public GapIssueCandidate {
        scenarioSamples = List.copyOf(scenarioSamples);
        sourceGapNos = List.copyOf(sourceGapNos);
    }
    @Override public Long auditTargetId(){return id;}
    @Override public String auditTargetCode(){return idempotencyKey;}
    @Override public String auditSummary(){return "候选="+idempotencyKey+",状态="+status+",Issue="+issueNumber;}
}
