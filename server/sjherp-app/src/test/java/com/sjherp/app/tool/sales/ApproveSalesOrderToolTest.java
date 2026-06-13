package com.sjherp.app.tool.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderNotFoundException;

/**
 * approve_sales_order 工具单测（M3-T11）：风险级别、权限点、doc_no 缺失失败、
 * operator 前缀 agent:、approve 调用 verify、领域拒绝转 fail。
 */
class ApproveSalesOrderToolTest {

    private SalesOrderAppService salesOrderAppService;
    private ApproveSalesOrderTool tool;
    private final ToolContext context = new ToolContext("session-3", "9", "审核销售订单");

    @BeforeEach
    void setUp() {
        salesOrderAppService = mock(SalesOrderAppService.class);
        tool = new ApproveSalesOrderTool(salesOrderAppService);
    }

    @Test
    void 风险级别HIGH_权限点sales_order() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("sales:order");
    }

    @Test
    void doc_no缺失_失败且不触碰AppService() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(salesOrderAppService);
    }

    @Test
    void 正常审核_operator记agent前缀_返回成功() {
        SalesOrder order = mock(SalesOrder.class);
        when(order.getDocNo()).thenReturn("SO-202606-0001");
        when(order.getStatus()).thenReturn(DocumentStatus.APPROVED);
        when(salesOrderAppService.approve(eq("SO-202606-0001"), eq("agent:9"))).thenReturn(order);

        ToolResult result = tool.execute(Map.of("doc_no", "SO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(salesOrderAppService).approve(eq("SO-202606-0001"), eq("agent:9"));
        assertThat(result.data()).containsEntry("docNo", "SO-202606-0001");
    }

    @Test
    void 单据不存在_转fail() {
        when(salesOrderAppService.approve(any(), any()))
                .thenThrow(new SalesOrderNotFoundException("SO-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "SO-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 状态流转拒绝_转fail() {
        when(salesOrderAppService.approve(any(), any()))
                .thenThrow(new IllegalStateTransitionException("SO-202606-0001",
                        DocumentStatus.APPROVED, DocumentStatus.APPROVED));

        ToolResult result = tool.execute(Map.of("doc_no", "SO-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("状态流转");
    }
}
