package com.sjherp.domain.gap;
public final class GapIssueNotFoundException extends RuntimeException {
    public GapIssueNotFoundException(long id) { super("gap issue candidate not found: " + id); }
}
