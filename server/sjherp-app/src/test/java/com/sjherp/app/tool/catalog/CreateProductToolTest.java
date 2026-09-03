package com.sjherp.app.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductCommand;
import com.sjherp.domain.catalog.InventoryCategory;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * create_product 工具单测（M2-T08）：单位按名称解析（成功 / 失败给清晰引导）、
 * 参数映射、审计操作人、领域校验拒绝转 fail、JSON Schema 必填校验。
 */
class CreateProductToolTest {

    private ProductService productService;
    private UnitService unitService;
    private CreateProductTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "新建商品 螺丝刀");

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        unitService = mock(UnitService.class);
        tool = new CreateProductTool(productService, unitService);
        when(unitService.findAll()).thenReturn(List.of(
                Unit.restore(1L, "个", 0, "t", Instant.now(), "t", Instant.now()),
                Unit.restore(2L, "箱", 0, "t", Instant.now(), "t", Instant.now())));
    }

    @Test
    void 风险级别为高_权限点已声明() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("catalog:create_product");
    }

    @Test
    void 单位按名称解析成功_命令映射正确() {
        Product created = Product.restore(9L, "SKU-202606-0009", "螺丝刀", null, null,
                InventoryCategory.RAW_MATERIAL, 1L, null,
                ArchiveStatus.ENABLED, null, List.of(), "agent:1", Instant.now(), "agent:1", Instant.now());
        when(productService.create(any(), any())).thenReturn(created);

        ToolResult result = tool.execute(Map.of(
                "name", "螺丝刀",
                "base_unit", "个",
                "inventory_category", "RAW_MATERIAL"), context);

        ArgumentCaptor<ProductCommand> captor = ArgumentCaptor.forClass(ProductCommand.class);
        verify(productService).create(captor.capture(), eq("agent:1"));
        ProductCommand command = captor.getValue();
        assertThat(command.code()).isNull(); // 编码自动生成
        assertThat(command.name()).isEqualTo("螺丝刀");
        assertThat(command.baseUnitId()).isEqualTo(1L);
        assertThat(command.inventoryCategory()).isEqualTo(InventoryCategory.RAW_MATERIAL);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("code", "SKU-202606-0009")
                .containsEntry("baseUnit", "个")
                .containsEntry("inventoryCategory", "RAW_MATERIAL");
    }

    @Test
    void 单位不存在_返回清晰错误并列出已有单位_不调用领域服务() {
        ToolResult result = tool.execute(Map.of("name", "螺丝刀", "base_unit", "把"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("把")          // 点名不存在的单位
                .contains("个、箱")      // 列出已有单位
                .contains("不存在");
        verify(productService, never()).create(any(), any());
    }

    @Test
    void 系统无任何单位时错误文案不同() {
        when(unitService.findAll()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("name", "螺丝刀", "base_unit", "把"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("还没有任何计量单位");
    }

    @Test
    void 领域校验拒绝转为失败结果() {
        when(productService.create(any(), any()))
                .thenThrow(new IllegalArgumentException("商品名称不能超过 200 个字符"));

        ToolResult result = tool.execute(Map.of(
                "name", "超长名称",
                "base_unit", "个",
                "inventory_category", "MERCHANDISE"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("商品名称不能超过 200 个字符");
    }

    @Test
    void schema校验_缺少必填name与base_unit拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("spec", "500ml"));
        assertThat(errors).anyMatch(e -> e.contains("name"));
        assertThat(errors).anyMatch(e -> e.contains("base_unit"));
    }

    @Test
    void schema校验_缺少存货类别或类别非法时拒绝() {
        JsonSchemaToolArgumentValidator validator = new JsonSchemaToolArgumentValidator();

        List<String> missing = validator.validate(tool.parameterSchema(), Map.of(
                "name", "螺丝刀", "base_unit", "个"));
        assertThat(missing).anyMatch(error -> error.contains("inventory_category"));

        ToolResult invalid = tool.execute(Map.of(
                "name", "螺丝刀",
                "base_unit", "个",
                "inventory_category", "UNKNOWN"), context);
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.error()).contains("存货类别");
    }
}
