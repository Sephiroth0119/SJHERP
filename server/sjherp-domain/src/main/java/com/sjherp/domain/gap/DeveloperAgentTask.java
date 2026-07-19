package com.sjherp.domain.gap;

import java.util.Objects;

/** 持久化开发任务的纯领域状态；不包含命令执行或 GitHub 副作用。 */
public record DeveloperAgentTask(
        long id, long candidateId, String idempotencyKey,
        DeveloperAgentTaskStatus status, String branchName, String workspacePath,
        String runnerKind, String leaseToken, int attemptCount,
        java.util.List<String> generatedArtifacts, boolean targetedTestsGreen,
        boolean fullTestsGreen, boolean ciGreen, String ciEvidence, boolean humanApproved,
        String failureType, String failureSummary, String runnerOutputSummary) {
    public DeveloperAgentTask {
        if (candidateId <= 0) throw new IllegalArgumentException("candidateId must be positive");
        Objects.requireNonNull(status);
        requireText(idempotencyKey, "idempotencyKey");
        requireText(branchName, "branchName");
        requireText(workspacePath, "workspacePath");
        requireText(runnerKind, "runnerKind");
        if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
        if (generatedArtifacts == null || ((status == DeveloperAgentTaskStatus.TESTING || status == DeveloperAgentTaskStatus.AWAITING_REVIEW || status == DeveloperAgentTaskStatus.APPROVED) && generatedArtifacts.isEmpty())) throw new IllegalArgumentException("generated artifacts required after testing");
        generatedArtifacts = java.util.List.copyOf(generatedArtifacts);
    }

    public DeveloperAgentTask transitionTo(DeveloperAgentTaskStatus target) {
        if (!status.canTransitionTo(target)) throw new IllegalStateException("invalid developer task transition");
        return new DeveloperAgentTask(id, candidateId, idempotencyKey, target, branchName, workspacePath,
                runnerKind, leaseToken, attemptCount, generatedArtifacts, targetedTestsGreen, fullTestsGreen, ciGreen, ciEvidence, humanApproved, failureType, failureSummary, runnerOutputSummary);
    }

    public DeveloperAgentTask approve() {
        if (status != DeveloperAgentTaskStatus.AWAITING_REVIEW || generatedArtifacts.isEmpty() || generatedArtifacts.stream().anyMatch(v -> v == null || v.isBlank() || v.equalsIgnoreCase("pending")) || !targetedTestsGreen || !fullTestsGreen || !ciGreen || ciEvidence == null || ciEvidence.isBlank())
            throw new IllegalStateException("CI must be green before approval");
        return new DeveloperAgentTask(id, candidateId, idempotencyKey, DeveloperAgentTaskStatus.APPROVED,
                branchName, workspacePath, runnerKind, leaseToken, attemptCount, generatedArtifacts, true, true, true, ciEvidence, true, failureType, failureSummary, runnerOutputSummary);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
