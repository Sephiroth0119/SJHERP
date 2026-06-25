package com.sjherp.app.tool.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.config.TransactionalMrpService;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpRunNotFoundException;

/**
 * MRP 运行查询工具单测（M5-T07）：只读 NORMAL，权限 production:mrp。
 */
class QueryMrpRunToolTest {

    private TransactionalMrpService service;
    private QueryMrpRunTool tool;
    private MrpRun runStub;
    private final ToolContext context = new ToolContext("session-1", "42", "查询 MRP");

    @BeforeEach
    void setUp() {
        service = mock(TransactionalMrpService.class);
        tool = new QueryMrpRunTool(service);
        runStub = mockRun();   // 先建桩，避免嵌套打桩 UnfinishedStubbing
    }

    private MrpRun mockRun() {
        MrpRun run = mock(MrpRun.class);
        when(run.getDocNo()).thenReturn("MRP-202606-0001");
        when(run.getRunAt()).thenReturn(Instant.parse("2026-06-15T00:00:00Z"));
        when(run.getSuggestions()).thenReturn(List.of());
        return run;
    }

    @Test
    void 风险级别NORMAL_权限点mrp() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("production:mrp");
    }

    @Test
    void 按doc_no精确查询_含建议明细_返回单笔() {
        when(service.get(eq("MRP-202606-0001"))).thenReturn(runStub);

        ToolResult result = tool.execute(Map.of("doc_no", "MRP-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "MRP-202606-0001");
        assertThat(result.data()).containsKey("suggestions");
        verify(service).get(eq("MRP-202606-0001"));
    }

    @Test
    void 无doc_no分页查询历史_返回列表() {
        PageResult<MrpRun> page = new PageResult<>(List.of(runStub), 1, 1, 10);
        when(service.searchHistory(anyInt(), anyInt())).thenReturn(page);

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 1L);
        verify(service).searchHistory(anyInt(), anyInt());
    }

    @Test
    void 运行不存在_转fail() {
        when(service.get(eq("MRP-NOT-EXIST")))
                .thenThrow(new MrpRunNotFoundException("MRP-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "MRP-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
    }
}
