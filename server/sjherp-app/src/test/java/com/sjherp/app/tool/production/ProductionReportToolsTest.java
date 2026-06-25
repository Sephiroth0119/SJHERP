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
import com.sjherp.app.production.ProductionReportAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLineInput;
import com.sjherp.domain.production.ProductionReportNotFoundException;
import com.sjherp.domain.production.ProductionReportQuery;

/**
 * 报工单 4 个 Agent 工具单测（M5-T07）：建/审/过账/查询。
 */
class ProductionReportToolsTest {

    private ProductionReportAppService service;
    private ProductionReport reportStub;
    private final ToolContext context = new ToolContext("session-1", "42", "报工操作");

    @BeforeEach
    void setUp() {
        service = mock(ProductionReportAppService.class);
        reportStub = mockReport();   // 先建桩，避免嵌套打桩 UnfinishedStubbing
    }

    private ProductionReport mockReport() {
        ProductionReport report = mock(ProductionReport.class);
        when(report.getDocNo()).thenReturn("PR-202606-0001");
        when(report.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(report.getCompletedQty()).thenReturn(new BigDecimal("10"));
        when(report.getScrapQty()).thenReturn(BigDecimal.ZERO);
        when(report.getLines()).thenReturn(List.of());
        return report;
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_风险级别HIGH_权限点report() {
        CreateProductionReportTool tool = new CreateProductionReportTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:report");
    }

    @Test
    void create_work_order_doc_no缺失_失败且不触碰服务() {
        CreateProductionReportTool tool = new CreateProductionReportTool(service);
        ToolResult result = tool.execute(Map.of(
                "warehouse_id", 1, "product_id", 2, "completed_qty", "10",
                "unit_id", 3, "lines", List.of(Map.of())), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("work_order_doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void create_completed_qty缺失_失败且不触碰服务() {
        CreateProductionReportTool tool = new CreateProductionReportTool(service);
        ToolResult result = tool.execute(Map.of(
                "work_order_doc_no", "WO-202606-0001", "warehouse_id", 1, "product_id", 2,
                "unit_id", 3, "lines", List.of(Map.of("reported_hours", "1", "unit_id", 3))), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("completed_qty");
        verifyNoInteractions(service);
    }

    @Test
    void create_正常建单_operator记agent前缀_返回成功() {
        CreateProductionReportTool tool = new CreateProductionReportTool(service);
        when(service.create(eq("WO-202606-0001"), eq(1L), eq(2L),
                eq(new BigDecimal("10")), any(), eq(3L), any(),
                ArgumentMatchers.<List<ProductionReportLineInput>>any(), eq("agent:42")))
                .thenReturn(reportStub);

        List<Map<String, Object>> lines = List.of(Map.of("reported_hours", "8", "unit_id", 3));
        ToolResult result = tool.execute(Map.of(
                "work_order_doc_no", "WO-202606-0001", "warehouse_id", 1, "product_id", 2,
                "completed_qty", "10", "unit_id", 3, "lines", lines), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "PR-202606-0001");
        verify(service).create(eq("WO-202606-0001"), eq(1L), eq(2L),
                eq(new BigDecimal("10")), any(), eq(3L), any(),
                ArgumentMatchers.<List<ProductionReportLineInput>>any(), eq("agent:42"));
    }

    // ---------------------------------------------------------------- approve

    @Test
    void approve_风险级别HIGH_权限点report() {
        ApproveProductionReportTool tool = new ApproveProductionReportTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:report");
    }

    @Test
    void approve_doc_no缺失_失败且不触碰服务() {
        ApproveProductionReportTool tool = new ApproveProductionReportTool(service);
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void approve_正常审核_operator记agent前缀_返回成功() {
        ApproveProductionReportTool tool = new ApproveProductionReportTool(service);
        when(service.approve(eq("PR-202606-0001"), eq("agent:42"))).thenReturn(reportStub);

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).approve(eq("PR-202606-0001"), eq("agent:42"));
    }

    // ---------------------------------------------------------------- post

    @Test
    void post_风险级别HIGH_权限点report() {
        PostProductionReportTool tool = new PostProductionReportTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:report");
    }

    @Test
    void post_正常过账_operator记agent前缀_返回成功() {
        PostProductionReportTool tool = new PostProductionReportTool(service);
        when(service.post(eq("PR-202606-0001"), eq("agent:42"))).thenReturn(reportStub);

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).post(eq("PR-202606-0001"), eq("agent:42"));
    }

    @Test
    void post_单据不存在_转fail() {
        PostProductionReportTool tool = new PostProductionReportTool(service);
        when(service.post(eq("PR-NOT-EXIST"), eq("agent:42")))
                .thenThrow(new ProductionReportNotFoundException("PR-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "PR-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    // ---------------------------------------------------------------- query

    @Test
    void query_风险级别NORMAL_权限点report() {
        QueryProductionReportTool tool = new QueryProductionReportTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("production:report");
    }

    @Test
    void query_按doc_no精确查询_返回单笔() {
        QueryProductionReportTool tool = new QueryProductionReportTool(service);
        when(service.get(eq("PR-202606-0001"))).thenReturn(reportStub);

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "PR-202606-0001");
        verify(service).get(eq("PR-202606-0001"));
    }

    @Test
    void query_无doc_no分页搜索_返回列表() {
        QueryProductionReportTool tool = new QueryProductionReportTool(service);
        PageResult<ProductionReport> page = new PageResult<>(List.of(reportStub), 1, 1, 10);
        when(service.search(ArgumentMatchers.<ProductionReportQuery>any())).thenReturn(page);

        ToolResult result = tool.execute(Map.of("work_order_doc_no", "WO-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 1L);
        verify(service).search(ArgumentMatchers.<ProductionReportQuery>any());
    }
}
