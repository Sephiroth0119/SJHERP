package com.sjherp.domain.gap;

import java.time.Instant;
import java.util.Optional;

public interface DeveloperAgentTaskRepository {
    DeveloperAgentTask createIfAbsent(DeveloperAgentTask task, String operator);
    Optional<DeveloperAgentTask> findById(long id);
    Optional<DeveloperAgentTask> findByCandidateId(long candidateId);
    Optional<String> claim(long id, Instant now);
    void transition(long id, DeveloperAgentTaskStatus expected, DeveloperAgentTaskStatus target, String leaseToken,
                    java.util.List<String> artifacts, boolean targeted, boolean full, boolean ciGreen, String ciEvidence, String outputSummary);
    void markFailed(long id, DeveloperAgentTaskStatus expected, String leaseToken, String failureType, String failureSummary, java.util.List<String> artifacts, boolean targeted, boolean full, boolean ci, String ciEvidence, String outputSummary);
    default void markFailed(long id, DeveloperAgentTaskStatus expected, String leaseToken, String failureType, String failureSummary) {
        markFailed(id, expected, leaseToken, failureType, failureSummary, java.util.List.of(), false, false, false, null, null);
    }
    void approve(long id, String operator);
    void cancel(long id, String operator);
    int reclaimExpired(Instant cutoff);
}
