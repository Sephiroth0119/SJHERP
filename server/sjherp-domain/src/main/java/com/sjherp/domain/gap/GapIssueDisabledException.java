package com.sjherp.domain.gap;
public final class GapIssueDisabledException extends RuntimeException {
    public GapIssueDisabledException() { super("GitHub Issue external writing is disabled"); }
    public GapIssueDisabledException(String message) { super(message); }
}
