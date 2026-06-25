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
import com.sjherp.app.production.MaterialReturnAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MaterialReturn;
import com.sjherp.domain.production.MaterialReturnLineInput;
import com.sjherp.domain.production.MaterialReturnNotFoundException;
import com.sjherp.domain.production.MaterialReturnQuery;

/**
 * 退料单 4 个 Agent 工具单测（M5-T07）：建/审/过账/查询。
 */
class MaterialReturnToolsTest {

    private MaterialReturnAppService service;
    private MaterialReturn returnStub;
    private final ToolContext context = new ToolContext("session-1", "42", "退料操作");

    @BeforeEach
    void setUp() {
        service = mock(MaterialReturnAppService.class);
        returnStub = mockReturn();   // 先建桩，避免嵌套打桩 UnfinishedStubbing
    }

    private MaterialReturn mockReturn() {
        MaterialReturn ret = mock(MaterialReturn.class);
        when(ret.getDocNo()).thenReturn("MR-202606-0001");
        when(ret.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(ret.totalReturnedCost()).thenReturn(new BigDecimal("50"));
        when(ret.getLines()).thenReturn(List.of());
        return ret;
    }

    // ---------------------------------------------------------------- create

    @Test
    void create_风险级别HIGH_权限点material() {
        CreateMaterialReturnTool tool = new CreateMaterialReturnTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void create_material_issue_doc_no缺失_失败且不触碰服务() {
        CreateMaterialReturnTool tool = new CreateMaterialReturnTool(service);
        ToolResult result = tool.execute(
                Map.of("warehouse_id", 1, "lines", List.of(Map.of())), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("material_issue_doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void create_正常建单_operator记agent前缀_返回成功() {
        CreateMaterialReturnTool tool = new CreateMaterialReturnTool(service);
        when(service.create(eq("MI-202606-0001"), eq(1L), any(),
                ArgumentMatchers.<List<MaterialReturnLineInput>>any(), eq("agent:42")))
                .thenReturn(returnStub);

        List<Map<String, Object>> lines = List.of(
                Map.of("product_id", 10, "quantity", "3", "unit_id", 2));
        ToolResult result = tool.execute(Map.of(
                "material_issue_doc_no", "MI-202606-0001", "warehouse_id", 1, "lines", lines), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "MR-202606-0001");
        verify(service).create(eq("MI-202606-0001"), eq(1L), any(),
                ArgumentMatchers.<List<MaterialReturnLineInput>>any(), eq("agent:42"));
    }

    // ---------------------------------------------------------------- approve

    @Test
    void approve_风险级别HIGH_权限点material() {
        ApproveMaterialReturnTool tool = new ApproveMaterialReturnTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void approve_doc_no缺失_失败且不触碰服务() {
        ApproveMaterialReturnTool tool = new ApproveMaterialReturnTool(service);
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(service);
    }

    @Test
    void approve_正常审核_operator记agent前缀_返回成功() {
        ApproveMaterialReturnTool tool = new ApproveMaterialReturnTool(service);
        when(service.approve(eq("MR-202606-0001"), eq("agent:42"))).thenReturn(returnStub);

        ToolResult result = tool.execute(Map.of("doc_no", "MR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).approve(eq("MR-202606-0001"), eq("agent:42"));
    }

    // ---------------------------------------------------------------- post

    @Test
    void post_风险级别HIGH_权限点material() {
        PostMaterialReturnTool tool = new PostMaterialReturnTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void post_正常过账_operator记agent前缀_返回成功() {
        PostMaterialReturnTool tool = new PostMaterialReturnTool(service);
        when(service.post(eq("MR-202606-0001"), eq("agent:42"))).thenReturn(returnStub);

        ToolResult result = tool.execute(Map.of("doc_no", "MR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(service).post(eq("MR-202606-0001"), eq("agent:42"));
    }

    @Test
    void post_单据不存在_转fail() {
        PostMaterialReturnTool tool = new PostMaterialReturnTool(service);
        when(service.post(eq("MR-NOT-EXIST"), eq("agent:42")))
                .thenThrow(new MaterialReturnNotFoundException("MR-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "MR-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    // ---------------------------------------------------------------- query

    @Test
    void query_风险级别NORMAL_权限点material() {
        QueryMaterialReturnTool tool = new QueryMaterialReturnTool(service);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("production:material");
    }

    @Test
    void query_按doc_no精确查询_返回单笔() {
        QueryMaterialReturnTool tool = new QueryMaterialReturnTool(service);
        when(service.get(eq("MR-202606-0001"))).thenReturn(returnStub);

        ToolResult result = tool.execute(Map.of("doc_no", "MR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("doc_no", "MR-202606-0001");
        verify(service).get(eq("MR-202606-0001"));
    }

    @Test
    void query_无doc_no分页搜索_返回列表() {
        QueryMaterialReturnTool tool = new QueryMaterialReturnTool(service);
        PageResult<MaterialReturn> page = new PageResult<>(List.of(returnStub), 1, 1, 10);
        when(service.search(ArgumentMatchers.<MaterialReturnQuery>any())).thenReturn(page);

        ToolResult result = tool.execute(Map.of("material_issue_doc_no", "MI-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 1L);
        verify(service).search(ArgumentMatchers.<MaterialReturnQuery>any());
    }
}
