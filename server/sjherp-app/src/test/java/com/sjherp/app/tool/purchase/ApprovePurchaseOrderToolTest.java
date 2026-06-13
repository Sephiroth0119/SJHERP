package com.sjherp.app.tool.purchase;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderNotFoundException;

/**
 * approve_purchase_order 工具单测（M3-T11）：风险级别、权限点、doc_no 缺失失败、
 * operator 前缀 agent:、approve 调用 verify、领域拒绝转 fail。
 */
class ApprovePurchaseOrderToolTest {

    private PurchaseOrderAppService purchaseOrderAppService;
    private ApprovePurchaseOrderTool tool;
    private final ToolContext context = new ToolContext("session-1", "42", "审核采购订单");

    @BeforeEach
    void setUp() {
        purchaseOrderAppService = mock(PurchaseOrderAppService.class);
        tool = new ApprovePurchaseOrderTool(purchaseOrderAppService);
    }

    @Test
    void 风险级别HIGH_权限点purchase_order() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("purchase:order");
    }

    @Test
    void doc_no缺失_失败且不触碰AppService() {
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(purchaseOrderAppService);
    }

    @Test
    void 正常审核_operator记agent前缀_返回成功() {
        PurchaseOrder order = mock(PurchaseOrder.class);
        when(order.getDocNo()).thenReturn("PO-202606-0001");
        when(order.getStatus()).thenReturn(DocumentStatus.APPROVED);
        when(purchaseOrderAppService.approve(eq("PO-202606-0001"), eq("agent:42"))).thenReturn(order);

        ToolResult result = tool.execute(Map.of("doc_no", "PO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(purchaseOrderAppService).approve(eq("PO-202606-0001"), eq("agent:42"));
        assertThat(result.data()).containsEntry("docNo", "PO-202606-0001");
    }

    @Test
    void 单据不存在_转fail() {
        when(purchaseOrderAppService.approve(eq("PO-NOT-EXIST"), eq("agent:42")))
                .thenThrow(new PurchaseOrderNotFoundException("PO-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "PO-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 状态流转拒绝_转fail() {
        when(purchaseOrderAppService.approve(eq("PO-202606-0001"), eq("agent:42")))
                .thenThrow(new IllegalStateTransitionException("PO-202606-0001",
                        DocumentStatus.APPROVED, DocumentStatus.APPROVED));

        ToolResult result = tool.execute(Map.of("doc_no", "PO-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("状态流转");
    }
}