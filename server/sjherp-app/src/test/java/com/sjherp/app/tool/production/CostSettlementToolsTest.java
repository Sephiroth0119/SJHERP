package com.sjherp.app.tool.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.production.ProductionCostSettlementAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLineInput;
import com.sjherp.domain.production.ProductionCostSettlementNotFoundException;
import com.sjherp.domain.production.ProductionCostSettlementQuery;

/**
 * 月末成本结转单 4 个 Agent 工具单测（M5-T07）：建/审/过账/查询。
 * 过账工具覆盖账期已关闭（PeriodClosedException）→ fail 含「账期」。
 */
class CostSettlementToolsTest {

    private ProductionCostSettlementAppService service;
    private ProductionCostSettlement settlementStub;
    private final ToolContext context = new ToolContext("session-1", "42", "成本结转操作");

    @BeforeEach
    void setUp() {
        service = mock(ProductionCostSettlementAppService.class);
        settlementStub = mockSettlement();   // 先建桩，避免在 when().thenReturn(mockXxx()) 内嵌套打桩（UnfinishedStubbing）
    }

    private ProductionCostSettlement mockSettlement() {
        ProductionCostSettlement settlement = mock(ProductionCostSettlement.class);
        when(settlement.getDocNo()).thenReturn("PC-202606-0001");
        when(settlement.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(settlement.getPeriod()).thenReturn("202606");
        when(settlement.getLines()).thenReturn(List.of());
        return settlement;
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_风险级别HIGH_权限点cost() {
        CreateCostSettlementTool tool = new CreateCostSettlementTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:cost");
    }

    @Test
    void create_period缺失_失败且不触碰服务() {
        CreateCostSettlementTool tool = new CreateCostSettlementTool(service);
        ToolResult result = tool.execute(
                Map.of("lines", List.of(Map.of("work_order_doc_no", "WO-202606-0001"))), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(service);
    }

    @Test
    void create_lines为空_失败且不触碰服务() {
        CreateCostSettlementTool tool = new CreateCostSettlementTool(service);
        ToolResult result = tool.execute(Map.of("period", "202606", "lines", List.of()), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("lines");
        verifyNoInteractions(service);
    }

    @Test
    void create_正常建单_operator记agent前缀_返回成功() {
        CreateCostSettlementTool tool = new CreateCostSettlementTool(service);
        when(service.create(eq("202606"), any(),
                ArgumentMatchers.<List<ProductionCostSettlementLineInput>>any(), eq("agent:42")))
                .thenReturn(settlementStub);

        List<Map<String, Object>> lines = List.of(Map.of("work_order_doc_no", "WO-202606-0001"));
        ToolResult result = tool.execute(Map.of("period", "202606", "lines", lines), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "PC-202606-0001");
        verify(service).create(eq("202606"), any(),
                ArgumentMatchers.<List<ProductionCostSettlementLineInput>>any(), eq("agent:42"));
    }

    // ---------------------------------------------------------------- approve

    @Test
    void approve_风险级别HIGH_权限点cost() {
        ApproveCostSettlementTool tool = new ApproveCostSettlementTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:cost");
    }

    @Test
    void approve_doc_no缺失_失败且不触碰服务() {
        ApproveCostSettlementTool tool = new ApproveCostSettlementTool(service);
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void approve_正常审核_operator记agent前缀_返回成功() {
        ApproveCostSettlementTool tool = new ApproveCostSettlementTool(service);
        when(service.approve(eq("PC-202606-0001"), eq("agent:42"))).thenReturn(settlementStub);

        ToolResult result = tool.execute(Map.of("doc_no", "PC-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).approve(eq("PC-202606-0001"), eq("agent:42"));
    }

    // ---------------------------------------------------------------- post

    @Test
    void post_风险级别HIGH_权限点cost() {
        PostCostSettlementTool tool = new PostCostSettlementTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:cost");
    }

    @Test
    void post_正常过账_operator记agent前缀_返回成功() {
        PostCostSettlementTool tool = new PostCostSettlementTool(service);
        when(service.post(eq("PC-202606-0001"), eq("agent:42"))).thenReturn(settlementStub);

        ToolResult result = tool.execute(Map.of("doc_no", "PC-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).post(eq("PC-202606-0001"), eq("agent:42"));
    }

    @Test
    void post_账期已关闭_转fail含账期() {
        PostCostSettlementTool tool = new PostCostSettlementTool(service);
        when(service.post(eq("PC-202606-0001"), eq("agent:42")))
                .thenThrow(new PeriodClosedException("202606"));

        ToolResult result = tool.execute(Map.of("doc_no", "PC-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("账期");
    }

    @Test
    void post_单据不存在_转fail() {
        PostCostSettlementTool tool = new PostCostSettlementTool(service);
        when(service.post(eq("PC-NOT-EXIST"), eq("agent:42")))
                .thenThrow(new ProductionCostSettlementNotFoundException("PC-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "PC-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    // ---------------------------------------------------------------- query

    @Test
    void query_风险级别NORMAL_权限点cost() {
        QueryCostSettlementTool tool = new QueryCostSettlementTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("production:cost");
    }

    @Test
    void query_按doc_no精确查询_返回单笔() {
        QueryCostSettlementTool tool = new QueryCostSettlementTool(service);
        when(service.get(eq("PC-202606-0001"))).thenReturn(settlementStub);

        ToolResult result = tool.execute(Map.of("doc_no", "PC-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "PC-202606-0001");
        verify(service).get(eq("PC-202606-0001"));
    }

    @Test
    void query_无doc_no分页搜索_返回列表() {
        QueryCostSettlementTool tool = new QueryCostSettlementTool(service);
        PageResult<ProductionCostSettlement> page = new PageResult<>(List.of(settlementStub), 1, 1, 10);
        when(service.search(ArgumentMatchers.<ProductionCostSettlementQuery>any())).thenReturn(page);

        ToolResult result = tool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 1L);
        verify(service).search(ArgumentMatchers.<ProductionCostSettlementQuery>any());
    }
}
