package com.sjherp.app.tool.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.production.KittingCheckAppService;
import com.sjherp.domain.production.KittingCheck;
import com.sjherp.domain.production.WorkOrderNotFoundException;

/**
 * 齐套检查工具单测（M5-T07）：只读 NORMAL，权限 production:material。
 */
class CheckKittingToolTest {

    private KittingCheckAppService service;
    private CheckKittingTool tool;
    private final ToolContext context = new ToolContext("session-1", "42", "齐套检查");

    @BeforeEach
    void setUp() {
        service = mock(KittingCheckAppService.class);
        tool = new CheckKittingTool(service);
    }

    @Test
    void 风险级别NORMAL_权限点material() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void work_order_doc_no缺失_失败且不触碰服务() {
        ToolResult result = tool.execute(Map.of("warehouse_id", 1), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("work_order_doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void 正常齐套检查_返回成功() {
        KittingCheck check = new KittingCheck("WO-202606-0001", 1L, true, List.of());
        when(service.check(eq("WO-202606-0001"), eq(1L))).thenReturn(check);

        ToolResult result = tool.execute(
                Map.of("work_order_doc_no", "WO-202606-0001", "warehouse_id", 1), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("work_order_doc_no", "WO-202606-0001");
        assertThat(result.data()).containsEntry("kitted", true);
        verify(service).check(eq("WO-202606-0001"), eq(1L));
    }

    @Test
    void 工单不存在_转fail() {
        when(service.check(eq("WO-NOT-EXIST"), eq(1L)))
                .thenThrow(new WorkOrderNotFoundException("WO-NOT-EXIST"));

        ToolResult result = tool.execute(
                Map.of("work_order_doc_no", "WO-NOT-EXIST", "warehouse_id", 1), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }
}
