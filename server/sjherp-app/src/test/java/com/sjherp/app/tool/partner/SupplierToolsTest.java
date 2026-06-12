package com.sjherp.app.tool.partner;

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
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierCommand;
import com.sjherp.domain.partner.SupplierQuery;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * search_suppliers / create_supplier 工具单测（M2-T08）：查询条件映射、
 * 创建命令映射与默认结算方式、审计操作人、领域校验拒绝、Schema 必填校验。
 */
class SupplierToolsTest {

    private SupplierService supplierService;
    private SearchSuppliersTool searchTool;
    private CreateSupplierTool createTool;
    private final ToolContext context = new ToolContext("session-1", "1", "供应商操作");

    @BeforeEach
    void setUp() {
        supplierService = mock(SupplierService.class);
        searchTool = new SearchSuppliersTool(supplierService);
        createTool = new CreateSupplierTool(supplierService);
    }

    private static Supplier supplier(String code, String name) {
        return Supplier.restore(11L, code, name, "李四", "13700137000", null, null,
                SettlementMethod.CASH, ArchiveStatus.ENABLED,
                "agent:1", Instant.now(), "agent:1", Instant.now());
    }

    @Test
    void 风险级别声明正确() {
        assertThat(searchTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(createTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(createTool.requiredPermission()).isEqualTo("partner:create_supplier");
    }

    @Test
    void 查询条件映射与列表字段() {
        when(supplierService.search(eq(new SupplierQuery("钢材", ArchiveStatus.ENABLED, 1, 10))))
                .thenReturn(new PageResult<>(List.of(supplier("SUP-202606-0001", "北方钢材")), 1, 1, 10));

        ToolResult result = searchTool.execute(Map.of("keyword", "钢材", "status", "ENABLED"), context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items.get(0))
                .containsEntry("name", "北方钢材")
                .containsEntry("settlementMethod", "现结");
    }

    @Test
    void 创建命令映射_默认月结_操作人记agent前缀() {
        when(supplierService.create(any(), any())).thenReturn(supplier("SUP-202606-0002", "某供应商"));

        ToolResult result = createTool.execute(Map.of("name", "某供应商", "contact_person", "李四"), context);

        ArgumentCaptor<SupplierCommand> captor = ArgumentCaptor.forClass(SupplierCommand.class);
        verify(supplierService).create(captor.capture(), eq("agent:1"));
        SupplierCommand command = captor.getValue();
        assertThat(command.code()).isNull();
        assertThat(command.name()).isEqualTo("某供应商");
        assertThat(command.contactPerson()).isEqualTo("李四");
        assertThat(command.settlementMethod()).isEqualTo(SettlementMethod.MONTHLY);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("code", "SUP-202606-0002");
    }

    @Test
    void 创建被领域校验拒绝转失败结果() {
        when(supplierService.create(any(), any()))
                .thenThrow(new IllegalArgumentException("供应商编码已存在: SUP-1"));

        ToolResult result = createTool.execute(Map.of("name", "某供应商"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("供应商编码已存在");
    }

    @Test
    void schema校验_创建缺少name拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(createTool.parameterSchema(), Map.of("contact_person", "李四"));
        assertThat(errors).anyMatch(e -> e.contains("name"));
    }
}
