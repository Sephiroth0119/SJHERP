package com.sjherp.domain.gap;

public record ClosureEvidence(String reference, String summary) {
    public ClosureEvidence {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("resolution reference required");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("resolution summary required");
    }
}
