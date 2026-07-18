package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.MemoryWriteSource;
import com.sjherp.domain.memory.StructuredMemoryCandidate;
import com.sjherp.domain.memory.MemoryEntryCommand;

class MemoryWriteChannelTest {
    @Test
    void facts_are_canonical_and_sorted() {
        assertThat(MemoryWriteChannel.canonicalContent(Map.of("threshold", "500000", "unit", "CNY")))
                .isEqualTo("{\"threshold\":\"500000\",\"unit\":\"CNY\"}");
    }

    @Test
    void canonical_content_escapes_json_control_characters() {
        assertThat(MemoryWriteChannel.canonicalContent(Map.of("note", "a\tb\b\f\u0001")))
                .isEqualTo("{\"note\":\"a\\tb\\b\\f\\u0001\"}");
    }

    @Test
    void candidate_carries_trace_and_hitl_requirement() {
        StructuredMemoryCandidate candidate = new StructuredMemoryCandidate(
                MemoryType.BUSINESS_TERM, "大客户", Map.of("annualPurchase", ">500000"),
                MemoryWriteSource.GAP_RECORD, "GAP-202607-0001", "session-1", true);
        assertThat(candidate.sourceRef()).isEqualTo("GAP-202607-0001");
        assertThat(candidate.sessionId()).isEqualTo("session-1");
        assertThat(candidate.requiresHumanApproval()).isTrue();
    }

    @Test
    void approved_candidate_preserves_primary_source_reference() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryWriteChannel channel = new MemoryWriteChannel(memoryService);
        StructuredMemoryCandidate candidate = new StructuredMemoryCandidate(
                MemoryType.BUSINESS_TERM, "大客户", Map.of("threshold", "500000"),
                MemoryWriteSource.AGENT_SESSION, "session-1", "session-1", true);

        channel.approveAndWrite(candidate, "agent:42");

        ArgumentCaptor<MemoryEntryCommand> captor = ArgumentCaptor.forClass(MemoryEntryCommand.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(memoryService).createIdempotent(keyCaptor.capture(), captor.capture(), eq("agent:42"));
        assertThat(keyCaptor.getValue()).startsWith("write:");
        assertThat(captor.getValue().sourceRef()).isEqualTo("session-1");
        assertThat(captor.getValue().content()).isEqualTo("{\"threshold\":\"500000\"}");
        assertThat(captor.getValue().validFrom()).isNull();
    }

    @Test
    void missing_approver_rejects_hitl_candidate() {
        MemoryWriteChannel channel = new MemoryWriteChannel(mock(MemoryService.class));
        StructuredMemoryCandidate candidate = new StructuredMemoryCandidate(
                MemoryType.BUSINESS_TERM, "大客户", Map.of("threshold", "500000"),
                MemoryWriteSource.AGENT_SESSION, "session-1", "session-1", true);
        assertThatThrownBy(() -> channel.approveAndWrite(candidate, " "))
                .isInstanceOf(IllegalStateException.class);
    }
}
