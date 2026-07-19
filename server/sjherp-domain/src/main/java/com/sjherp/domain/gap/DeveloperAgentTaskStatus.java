package com.sjherp.domain.gap;

public enum DeveloperAgentTaskStatus {
    QUEUED, RUNNING, TESTING, AWAITING_REVIEW, APPROVED, FAILED, CANCELLED;

    public boolean canTransitionTo(DeveloperAgentTaskStatus target) {
        return switch (this) {
            case QUEUED -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == TESTING || target == FAILED || target == CANCELLED;
            case TESTING -> target == AWAITING_REVIEW || target == FAILED;
            case AWAITING_REVIEW -> target == APPROVED || target == FAILED || target == CANCELLED;
            case FAILED -> target == QUEUED || target == CANCELLED;
            case APPROVED, CANCELLED -> false;
        };
    }
}
