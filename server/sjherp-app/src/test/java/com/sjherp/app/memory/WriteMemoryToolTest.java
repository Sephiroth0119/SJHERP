package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.gap.BusinessModule;
import com.sjherp.domain.gap.GapRecord;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.domain.gap.GapRecordNotFoundException;
import com.sjherp.domain.gap.GapSeverity;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.MemoryWriteSource;
import com.sjherp.domain.memory.StructuredMemoryCandidate;

class WriteMemoryToolTest {
    private final MemoryWriteChannel channel = mock(MemoryWriteChannel.class);
    private final GapRecordService gapService = mock(GapRecordService.class);
    private final WriteMemoryTool tool = new WriteMemoryTool(channel, gapService);
    private final ToolContext context = new ToolContext("session-1", "42", "大客户按年采购额判断");

    @Test
    void write_is_high_risk_and_requires_memory_permission() {
        assertThat(tool.name()).isEqualTo("write_memory");
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("memory:manage");
    }

    @Test
    void user_input_uses_authenticated_session_as_trace() {
        MemoryEntry saved = mock(MemoryEntry.class);
        when(saved.getMemoryNo()).thenReturn("MEM-202607-0001");
        when(saved.getVersion()).thenReturn(1);
        when(channel.approveAndWrite(any(), eq("agent:42"))).thenReturn(saved);

        ToolResult result = tool.execute(Map.of(
                "type", "BUSINESS_TERM",
                "title", "大客户口径",
                "facts", Map.of("term", "大客户", "annual_purchase_threshold", "500000", "currency", "CNY"),
                "source_kind", "USER_INPUT"), context);

        ArgumentCaptor<StructuredMemoryCandidate> captor = ArgumentCaptor.forClass(StructuredMemoryCandidate.class);
        verify(channel).approveAndWrite(captor.capture(), eq("agent:42"));
        StructuredMemoryCandidate candidate = captor.getValue();
        assertThat(candidate.memoryType()).isEqualTo(MemoryType.BUSINESS_TERM);
        assertThat(candidate.source()).isEqualTo(MemoryWriteSource.AGENT_SESSION);
        assertThat(candidate.sourceRef()).isEqualTo("session-1");
        assertThat(candidate.sessionId()).isEqualTo("session-1");
        assertThat(candidate.requiresHumanApproval()).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("memoryNo", "MEM-202607-0001");
    }

    @Test
    void gap_solution_resolves_gap_number_from_domain_record() {
        GapRecord gap = new GapRecord("GAP-202607-0001", "session-origin", "缺少月结导出",
                "月末需要导出", "支持导出", "缺少导出能力", BusinessModule.FINANCE,
                GapSeverity.MEDIUM, "42", "agent:42");
        when(gapService.get(7L)).thenReturn(gap);
        MemoryEntry saved = mock(MemoryEntry.class);
        when(saved.getMemoryNo()).thenReturn("MEM-202607-0002");
        when(saved.getVersion()).thenReturn(1);
        when(channel.approveAndWrite(any(), eq("agent:42"))).thenReturn(saved);

        tool.execute(Map.of(
                "type", "GAP_SOLUTION", "title", "月结导出解决方案",
                "facts", Map.of("solution", "使用月结导出功能"),
                "source_kind", "GAP_RECORD", "gap_record_id", 7), context);

        ArgumentCaptor<StructuredMemoryCandidate> captor = ArgumentCaptor.forClass(StructuredMemoryCandidate.class);
        verify(channel).approveAndWrite(captor.capture(), eq("agent:42"));
        assertThat(captor.getValue().source()).isEqualTo(MemoryWriteSource.GAP_RECORD);
        assertThat(captor.getValue().sourceRef()).isEqualTo("GAP-202607-0001");
    }

    @Test
    void missing_gap_is_returned_as_tool_failure() {
        when(gapService.get(99L)).thenThrow(new GapRecordNotFoundException(99L));
        ToolResult result = tool.execute(Map.of(
                "type", "GAP_SOLUTION", "title", "方案",
                "facts", Map.of("solution", "新增导出"),
                "source_kind", "GAP_RECORD", "gap_record_id", 99), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("记忆写入被拒绝");
    }

    @Test
    void facts_values_must_remain_strings_to_preserve_decimal_precision() {
        ToolResult result = tool.execute(Map.of(
                "type", "BUSINESS_TERM", "title", "大客户口径",
                "facts", Map.of("annual_purchase_threshold", 500000.01),
                "source_kind", "USER_INPUT"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("facts 的值必须是字符串");
        verifyNoInteractions(channel);
    }

    @Test
    void unknown_source_kind_is_rejected_in_the_tool_boundary() {
        ToolResult result = tool.execute(Map.of(
                "type", "BUSINESS_TERM", "title", "大客户口径",
                "facts", Map.of("threshold", "500000"),
                "source_kind", "SYSTEM"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("source_kind 仅支持 USER_INPUT 或 GAP_RECORD");
        verifyNoInteractions(channel);
    }
}
