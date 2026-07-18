package com.sjherp.domain.memory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StructuredMemoryCandidateTest {
    @Test
    void rejects_blank_fact_key_or_value() {
        assertThatThrownBy(() -> candidate(Map.of(" ", "500000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(Map.of("threshold", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_more_than_fifty_facts() {
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i < 51; i++) facts.put("key" + i, "value" + i);
        assertThatThrownBy(() -> candidate(facts)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gap_solution_must_trace_to_gap_record() {
        assertThatThrownBy(() -> new StructuredMemoryCandidate(MemoryType.GAP_SOLUTION,
                "解决方案", Map.of("solution", "增加月结导出"), MemoryWriteSource.AGENT_SESSION,
                "session-1", "session-1", true)).isInstanceOf(IllegalArgumentException.class);
    }

    private static StructuredMemoryCandidate candidate(Map<String, String> facts) {
        return new StructuredMemoryCandidate(MemoryType.BUSINESS_TERM, "大客户", facts,
                MemoryWriteSource.AGENT_SESSION, "session-1", "session-1", true);
    }
}
