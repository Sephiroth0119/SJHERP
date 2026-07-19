package com.sjherp.domain.gap;

import java.util.List;
import java.util.Optional;

public interface GapIssueCandidateRepository {
    GapIssueCandidate upsert(GapIssueCandidate candidate);
    void addSources(long candidateId, List<String> gapNos);
    List<GapIssueCandidate> findAll();
    Optional<GapIssueCandidate> findById(long id);
    boolean claimForSend(long id);
    int reclaimExpiredSending(java.time.Instant cutoff);
    void markApproved(long id, String operator);
    void markSent(long id, long number, String url);
    void markFailed(long id, String failureType);
}
