package com.sjherp.app.tool.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseCommand;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * search_warehouses / create_warehouse 工具单测（M2-T08）：查询条件映射、
 * 创建命令映射、审计操作人、领域校验拒绝、Schema 必填校验。
 */
class WarehouseToolsTest {

    private WarehouseService warehouseService;
    private SearchWarehousesTool searchTool;
    private CreateWarehouseTool createTool;
    private final ToolContext context = new ToolContext("session-1", "1", "仓库操作");

    @BeforeEach
    void setUp() {
        warehouseService = mock(WarehouseService.class);
        searchTool = new SearchWarehousesTool(warehouseService);
        createTool = new CreateWarehouseTool(warehouseService);
    }

    private static Warehouse warehouse(String code, String name) {
        return Warehouse.restore(5L, code, name, "园区路 1 号", "老王", false, ArchiveStatus.ENABLED,
                "agent:1", Instant.now(), "agent:1", Instant.now());
    }

    @Test
    void 风险级别声明正确() {
        assertThat(searchTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(createTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(createTool.requiredPermission()).isEqualTo("warehouse:create_warehouse");
    }

    @Test
    void 查询条件映射与列表字段() {
        when(warehouseService.search(eq(new WarehouseQuery("主仓", null, 1, 10))))
                .thenReturn(new PageResult<>(List.of(warehouse("WH-202606-0001", "主仓库")), 1, 1, 10));

        ToolResult result = searchTool.execute(Map.of("keyword", "主仓"), context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items.get(0))
                .containsEntry("code", "WH-202606-0001")
                .containsEntry("name", "主仓库")
                .containsEntry("manager", "老王")
                .containsEntry("status", "启用");
    }

    @Test
    void 创建命令映射_操作人记agent前缀() {
        when(warehouseService.create(any(), any())).thenReturn(warehouse("WH-202606-0002", "二号仓"));

        ToolResult result = createTool.execute(
                Map.of("name", "二号仓", "address", "园区路 2 号", "manager", "老王"), context);

        ArgumentCaptor<WarehouseCommand> captor = ArgumentCaptor.forClass(WarehouseCommand.class);
        verify(warehouseService).create(captor.capture(), eq("agent:1"));
        WarehouseCommand command = captor.getValue();
        assertThat(command.code()).isNull();
        assertThat(command.name()).isEqualTo("二号仓");
        assertThat(command.address()).isEqualTo("园区路 2 号");
        assertThat(command.manager()).isEqualTo("老王");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("code", "WH-202606-0002");
    }

    @Test
    void 创建被领域校验拒绝转失败结果() {
        when(warehouseService.create(any(), any()))
                .thenThrow(new IllegalArgumentException("仓库编码已存在: WH-1"));

        ToolResult result = createTool.execute(Map.of("name", "二号仓"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("仓库编码已存在");
    }

    @Test
    void schema校验_创建缺少name拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(createTool.parameterSchema(), Map.of("address", "园区路"));
        assertThat(errors).anyMatch(e -> e.contains("name"));
    }
}
