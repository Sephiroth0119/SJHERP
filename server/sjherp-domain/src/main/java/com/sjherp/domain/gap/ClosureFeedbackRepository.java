package com.sjherp.domain.gap;

public interface ClosureFeedbackRepository {
    boolean claim(long taskId, long candidateId, String evidenceReference, String evidenceSummary, String operator);
}
