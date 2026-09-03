package com.sjherp.app.tool.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.WorkOrder;
import com.sjherp.domain.production.WorkOrderNotFoundException;
import com.sjherp.domain.production.WorkOrderQuery;

/**
 * 工单 8 个 Agent 工具单测（M5-T07）：风险级别、权限点、必填字段缺失失败、
 * operator 记 agent: 前缀、领域异常转 fail。
 */
class WorkOrderToolsTest {

    private TransactionalWorkOrderService service;
    private WorkOrder workOrderStub;
    private final ToolContext context = new ToolContext("session-1", "42", "工单操作");

    @BeforeEach
    void setUp() {
        service = mock(TransactionalWorkOrderService.class);
        workOrderStub = mockWorkOrder();   // 先建桩，避免嵌套打桩 UnfinishedStubbing
    }

    /** 构造仅 toData 所需 getter 的工单 mock。 */
    private WorkOrder mockWorkOrder() {
        WorkOrder wo = mock(WorkOrder.class);
        when(wo.getDocNo()).thenReturn("WO-202606-0001");
        when(wo.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(wo.getPlannedQty()).thenReturn(new BigDecimal("10"));
        return wo;
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_风险级别HIGH_权限点wo() {
        CreateWorkOrderTool tool = new CreateWorkOrderTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void create_planned_qty缺失_失败且不触碰服务() {
        CreateWorkOrderTool tool = new CreateWorkOrderTool(service);
        ToolResult result = tool.execute(Map.of("product_id", 1, "unit_id", 2), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("planned_qty");
        verifyNoInteractions(service);
    }

    @Test
    void create_正常建单_operator记agent前缀_返回成功() {
        CreateWorkOrderTool tool = new CreateWorkOrderTool(service);
        WorkOrder wo = mockWorkOrder();
        when(service.createManual(eq(1L), eq(new BigDecimal("10")), eq(2L),
                any(), any(), any(), any(), any(), any(), eq("agent:42"))).thenReturn(wo);

        ToolResult result = tool.execute(
                Map.of("product_id", 1, "planned_qty", "10", "unit_id", 2), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "WO-202606-0001");
        verify(service).createManual(eq(1L), eq(new BigDecimal("10")), eq(2L),
                any(), any(), any(), any(), any(), any(), eq("agent:42"));
    }

    // ---------------------------------------------------------------- create from mrp

    @Test
    void createFromMrp_风险级别HIGH_权限点wo() {
        CreateWorkOrderFromMrpTool tool = new CreateWorkOrderFromMrpTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void createFromMrp_mrp_run_doc_no缺失_失败且不触碰服务() {
        CreateWorkOrderFromMrpTool tool = new CreateWorkOrderFromMrpTool(service);
        ToolResult result = tool.execute(Map.of("product_id", 1), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("mrp_run_doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void createFromMrp_正常转单_operator记agent前缀_返回成功() {
        CreateWorkOrderFromMrpTool tool = new CreateWorkOrderFromMrpTool(service);
        WorkOrder wo = mockWorkOrder();
        when(service.createFromSuggestion(eq("MRP-202606-0001"), eq(1L), eq("agent:42"))).thenReturn(wo);

        ToolResult result = tool.execute(
                Map.of("mrp_run_doc_no", "MRP-202606-0001", "product_id", 1), context);

        assertThat(result.success()).isTrue();
        verify(service).createFromSuggestion(eq("MRP-202606-0001"), eq(1L), eq("agent:42"));
    }

    // ---------------------------------------------------------------- release

    @Test
    void release_风险级别HIGH_权限点wo() {
        ReleaseWorkOrderTool tool = new ReleaseWorkOrderTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void release_doc_no缺失_失败且不触碰服务() {
        ReleaseWorkOrderTool tool = new ReleaseWorkOrderTool(service);
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void release_正常下达_operator记agent前缀_返回成功() {
        ReleaseWorkOrderTool tool = new ReleaseWorkOrderTool(service);
        when(service.release(eq("WO-202606-0001"), eq("agent:42"))).thenReturn(workOrderStub);

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).release(eq("WO-202606-0001"), eq("agent:42"));
    }

    @Test
    void release_状态流转拒绝_转fail() {
        ReleaseWorkOrderTool tool = new ReleaseWorkOrderTool(service);
        when(service.release(eq("WO-202606-0001"), eq("agent:42")))
                .thenThrow(new IllegalStateTransitionException("WO-202606-0001",
                        DocumentStatus.APPROVED, DocumentStatus.APPROVED));

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("状态流转");
    }

    // ---------------------------------------------------------------- start

    @Test
    void start_风险级别HIGH_权限点wo() {
        StartWorkOrderTool tool = new StartWorkOrderTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void start_正常投产_operator记agent前缀_返回成功() {
        StartWorkOrderTool tool = new StartWorkOrderTool(service);
        when(service.start(eq("WO-202606-0001"), eq("agent:42"))).thenReturn(workOrderStub);

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).start(eq("WO-202606-0001"), eq("agent:42"));
    }

    @Test
    void start_单据不存在_转fail() {
        StartWorkOrderTool tool = new StartWorkOrderTool(service);
        when(service.start(eq("WO-NOT-EXIST"), eq("agent:42")))
                .thenThrow(new WorkOrderNotFoundException("WO-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "WO-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    // ---------------------------------------------------------------- complete

    @Test
    void complete_风险级别HIGH_权限点wo() {
        CompleteWorkOrderTool tool = new CompleteWorkOrderTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void complete_正常完工_operator记agent前缀_返回成功() {
        CompleteWorkOrderTool tool = new CompleteWorkOrderTool(service);
        when(service.complete(eq("WO-202606-0001"), eq("agent:42"))).thenReturn(workOrderStub);

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).complete(eq("WO-202606-0001"), eq("agent:42"));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancel_风险级别HIGH_权限点wo() {
        CancelWorkOrderTool tool = new CancelWorkOrderTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void cancel_正常取消_operator记agent前缀_返回成功() {
        CancelWorkOrderTool tool = new CancelWorkOrderTool(service);
        when(service.cancel(eq("WO-202606-0001"), eq("agent:42"))).thenReturn(workOrderStub);

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).cancel(eq("WO-202606-0001"), eq("agent:42"));
    }

    // ---------------------------------------------------------------- reverse

    @Test
    void reverse_风险级别HIGH_权限点wo() {
        ReverseWorkOrderTool tool = new ReverseWorkOrderTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void reverse_正常冲销_operator记agent前缀_返回成功() {
        ReverseWorkOrderTool tool = new ReverseWorkOrderTool(service);
        when(service.reverse(eq("WO-202606-0001"), eq("agent:42"))).thenReturn(workOrderStub);

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).reverse(eq("WO-202606-0001"), eq("agent:42"));
    }

    @Test
    void reverse_投产后冲销被拒_转fail() {
        ReverseWorkOrderTool tool = new ReverseWorkOrderTool(service);
        when(service.reverse(eq("WO-202606-0001"), eq("agent:42")))
                .thenThrow(new IllegalStateTransitionException("WO-202606-0001",
                        DocumentStatus.EXECUTING, DocumentStatus.REVERSED));

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("状态流转");
    }

    // ---------------------------------------------------------------- query

    @Test
    void query_风险级别NORMAL_权限点wo() {
        QueryWorkOrderTool tool = new QueryWorkOrderTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("production:wo");
    }

    @Test
    void query_按doc_no精确查询_返回单笔() {
        QueryWorkOrderTool tool = new QueryWorkOrderTool(service);
        when(service.get(eq("WO-202606-0001"))).thenReturn(workOrderStub);

        ToolResult result = tool.execute(Map.of("doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "WO-202606-0001");
        verify(service).get(eq("WO-202606-0001"));
    }

    @Test
    void query_无doc_no分页搜索_返回列表() {
        QueryWorkOrderTool tool = new QueryWorkOrderTool(service);
        PageResult<WorkOrder> page = new PageResult<>(List.of(workOrderStub), 1, 1, 10);
        when(service.search(ArgumentMatchers.<WorkOrderQuery>any())).thenReturn(page);

        ToolResult result = tool.execute(Map.of("status", "DRAFT"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 1L);
        verify(service).search(ArgumentMatchers.<WorkOrderQuery>any());
    }
}
