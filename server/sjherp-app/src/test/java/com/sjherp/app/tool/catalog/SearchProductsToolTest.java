package com.sjherp.app.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * search_products 工具单测（M2-T08）：查询条件映射（关键字/状态/页大小 10）、
 * 单位名称解析、精简列表字段、超量提示、JSON Schema 枚举校验。
 */
class SearchProductsToolTest {

    private ProductService productService;
    private UnitService unitService;
    private SearchProductsTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "查商品");

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        unitService = mock(UnitService.class);
        tool = new SearchProductsTool(productService, unitService);
        when(unitService.findAll()).thenReturn(List.of(
                Unit.restore(1L, "个", 0, "t", Instant.now(), "t", Instant.now()),
                Unit.restore(2L, "箱", 0, "t", Instant.now(), "t", Instant.now())));
    }

    private static Product product(long id, String code, String name, long baseUnitId) {
        return Product.restore(id, code, name, "500ml", null, baseUnitId, null,
                ArchiveStatus.ENABLED, null, List.of(),
                "t", Instant.now(), "t", Instant.now());
    }

    @Test
    void 风险级别为普通() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isNull();
    }

    @Test
    void 查询条件映射_关键字加状态_固定第一页十条() {
        when(productService.search(eq(new ProductQuery("水", ArchiveStatus.ENABLED, 1, 10))))
                .thenReturn(new PageResult<>(List.of(product(1, "SKU-202606-0001", "矿泉水", 1)), 1, 1, 10));

        ToolResult result = tool.execute(Map.of("keyword", "水", "status", "ENABLED"), context);

        verify(productService).search(eq(new ProductQuery("水", ArchiveStatus.ENABLED, 1, 10)));
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 1L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0))
                .containsEntry("code", "SKU-202606-0001")
                .containsEntry("name", "矿泉水")
                .containsEntry("spec", "500ml")
                .containsEntry("baseUnit", "个")
                .containsEntry("status", "启用");
    }

    @Test
    void 无参数查询_不过滤_空结果() {
        when(productService.search(eq(new ProductQuery(null, null, 1, 10))))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 10));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("total", 0L);
        assertThat((List<?>) result.data().get("items")).isEmpty();
    }

    @Test
    void 总数超过返回条数时带缩小范围提示() {
        when(productService.search(eq(new ProductQuery(null, null, 1, 10))))
                .thenReturn(new PageResult<>(List.of(product(1, "SKU-1", "甲", 1)), 25, 1, 10));

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(String.valueOf(result.data().get("note"))).contains("25");
    }

    @Test
    void schema校验_非法状态枚举拒绝() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("status", "ALL"));
        assertThat(errors).isNotEmpty();
    }
}
