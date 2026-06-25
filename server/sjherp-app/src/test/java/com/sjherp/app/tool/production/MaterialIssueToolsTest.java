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
import com.sjherp.app.production.MaterialIssueAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLineInput;
import com.sjherp.domain.production.MaterialIssueNotFoundException;
import com.sjherp.domain.production.MaterialIssueQuery;

/**
 * 领料单 4 个 Agent 工具单测（M5-T07）：建/审/过账/查询。
 */
class MaterialIssueToolsTest {

    private MaterialIssueAppService service;
    private MaterialIssue issueStub;
    private final ToolContext context = new ToolContext("session-1", "42", "领料操作");

    @BeforeEach
    void setUp() {
        service = mock(MaterialIssueAppService.class);
        issueStub = mockIssue();   // 先建桩，避免嵌套打桩 UnfinishedStubbing
    }

    private MaterialIssue mockIssue() {
        MaterialIssue issue = mock(MaterialIssue.class);
        when(issue.getDocNo()).thenReturn("MI-202606-0001");
        when(issue.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(issue.totalIssuedCost()).thenReturn(new BigDecimal("100"));
        when(issue.getLines()).thenReturn(List.of());
        return issue;
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_风险级别HIGH_权限点material() {
        CreateMaterialIssueTool tool = new CreateMaterialIssueTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void create_work_order_doc_no缺失_失败且不触碰服务() {
        CreateMaterialIssueTool tool = new CreateMaterialIssueTool(service);
        ToolResult result = tool.execute(
                Map.of("warehouse_id", 1, "lines", List.of(Map.of())), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("work_order_doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void create_lines为空_失败且不触碰服务() {
        CreateMaterialIssueTool tool = new CreateMaterialIssueTool(service);
        ToolResult result = tool.execute(
                Map.of("work_order_doc_no", "WO-202606-0001", "warehouse_id", 1, "lines", List.of()),
                context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("lines");
        verifyNoInteractions(service);
    }

    @Test
    void create_正常建单_operator记agent前缀_返回成功() {
        CreateMaterialIssueTool tool = new CreateMaterialIssueTool(service);
        when(service.create(eq("WO-202606-0001"), eq(1L), any(),
                ArgumentMatchers.<List<MaterialIssueLineInput>>any(), eq("agent:42")))
                .thenReturn(issueStub);

        List<Map<String, Object>> lines = List.of(
                Map.of("product_id", 10, "quantity", "5", "unit_id", 2));
        ToolResult result = tool.execute(Map.of(
                "work_order_doc_no", "WO-202606-0001", "warehouse_id", 1, "lines", lines), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "MI-202606-0001");
        verify(service).create(eq("WO-202606-0001"), eq(1L), any(),
                ArgumentMatchers.<List<MaterialIssueLineInput>>any(), eq("agent:42"));
    }

    // ---------------------------------------------------------------- approve

    @Test
    void approve_风险级别HIGH_权限点material() {
        ApproveMaterialIssueTool tool = new ApproveMaterialIssueTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void approve_doc_no缺失_失败且不触碰服务() {
        ApproveMaterialIssueTool tool = new ApproveMaterialIssueTool(service);
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void approve_正常审核_operator记agent前缀_返回成功() {
        ApproveMaterialIssueTool tool = new ApproveMaterialIssueTool(service);
        when(service.approve(eq("MI-202606-0001"), eq("agent:42"))).thenReturn(issueStub);

        ToolResult result = tool.execute(Map.of("doc_no", "MI-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).approve(eq("MI-202606-0001"), eq("agent:42"));
    }

    @Test
    void approve_单据不存在_转fail() {
        ApproveMaterialIssueTool tool = new ApproveMaterialIssueTool(service);
        when(service.approve(eq("MI-NOT-EXIST"), eq("agent:42")))
                .thenThrow(new MaterialIssueNotFoundException("MI-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "MI-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    // ---------------------------------------------------------------- post

    @Test
    void post_风险级别HIGH_权限点material() {
        PostMaterialIssueTool tool = new PostMaterialIssueTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void post_正常过账_operator记agent前缀_返回成功() {
        PostMaterialIssueTool tool = new PostMaterialIssueTool(service);
        when(service.post(eq("MI-202606-0001"), eq("agent:42"))).thenReturn(issueStub);

        ToolResult result = tool.execute(Map.of("doc_no", "MI-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).post(eq("MI-202606-0001"), eq("agent:42"));
    }

    @Test
    void post_库存不足_转fail() {
        PostMaterialIssueTool tool = new PostMaterialIssueTool(service);
        when(service.post(eq("MI-202606-0001"), eq("agent:42")))
                .thenThrow(new InsufficientStockException(1L, 10L,
                        BigDecimal.ZERO, new BigDecimal("5")));

        ToolResult result = tool.execute(Map.of("doc_no", "MI-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("库存不足");
    }

    @Test
    void post_状态流转拒绝_转fail() {
        PostMaterialIssueTool tool = new PostMaterialIssueTool(service);
        when(service.post(eq("MI-202606-0001"), eq("agent:42")))
                .thenThrow(new IllegalStateTransitionException("MI-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.COMPLETED));

        ToolResult result = tool.execute(Map.of("doc_no", "MI-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("状态流转");
    }

    // ---------------------------------------------------------------- query

    @Test
    void query_风险级别NORMAL_权限点material() {
        QueryMaterialIssueTool tool = new QueryMaterialIssueTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void query_按doc_no精确查询_返回单笔() {
        QueryMaterialIssueTool tool = new QueryMaterialIssueTool(service);
        when(service.get(eq("MI-202606-0001"))).thenReturn(issueStub);

        ToolResult result = tool.execute(Map.of("doc_no", "MI-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "MI-202606-0001");
        verify(service).get(eq("MI-202606-0001"));
    }

    @Test
    void query_无doc_no分页搜索_返回列表() {
        QueryMaterialIssueTool tool = new QueryMaterialIssueTool(service);
        PageResult<MaterialIssue> page = new PageResult<>(List.of(issueStub), 1, 1, 10);
        when(service.search(ArgumentMatchers.<MaterialIssueQuery>any())).thenReturn(page);

        ToolResult result = tool.execute(Map.of("work_order_doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 1L);
        verify(service).search(ArgumentMatchers.<MaterialIssueQuery>any());
    }
}
