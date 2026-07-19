package com.sjherp.domain.gap;

import java.util.List;

public record GapIssueCandidate(long id, String idempotencyKey, String clusterKey,
        BusinessModule businessModule, GapSeverity severity, String title,
        List<String> scenarioSamples, String expectedBehavior, String missingCapability,
        List<String> sourceGapNos, GapIssueStatus status, Long issueNumber, String issueUrl) {
    public GapIssueCandidate {
        scenarioSamples = List.copyOf(scenarioSamples);
        sourceGapNos = List.copyOf(sourceGapNos);
    }
}
