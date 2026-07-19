package com.sjherp.domain.gap;

public interface GapIssueDeliveryFinalizer {
    java.util.Optional<String> claimDelivery(long candidateId, String operator);
    void finalizeDelivery(GapIssueCandidate candidate, String leaseToken, long number, String url, String operator);
    void failDelivery(long candidateId, String leaseToken, String failureType, String operator);
}
