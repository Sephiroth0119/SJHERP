package com.sjherp.domain.gap;

import java.util.List;
import java.util.Optional;

public interface GapIssueCandidateRepository {
    GapIssueCandidate upsert(GapIssueCandidate candidate);
    List<GapIssueCandidate> findAll();
    Optional<GapIssueCandidate> findById(long id);
    boolean claimForSend(long id);
    void markApproved(long id, String operator);
    void markSent(long id, long number, String url);
    void markFailed(long id, String failureType);
}
