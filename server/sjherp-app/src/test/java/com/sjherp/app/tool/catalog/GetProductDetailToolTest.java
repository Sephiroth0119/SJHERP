package com.sjherp.app.tool.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.domain.catalog.CatalogNotFoundException;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitConversion;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;

/**
 * get_product_detail 工具单测（M2-T08）：按 id / 按编码精确匹配 / 两参皆缺 /
 * 不存在 → 失败回灌；换算关系（含 meaning 文案与字符串承载的换算率）。
 */
class GetProductDetailToolTest {

    private ProductService productService;
    private UnitService unitService;
    private GetProductDetailTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "查商品详情");

    private final Product product = Product.restore(7L, "SKU-202606-0007", "矿泉水", "500ml", null,
            1L, "6901234567890", ArchiveStatus.ENABLED, null,
            List.of(new UnitConversion(2L, new BigDecimal("12"))),
            "t", Instant.now(), "t", Instant.now());

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        unitService = mock(UnitService.class);
        tool = new GetProductDetailTool(productService, unitService);
        when(unitService.findAll()).thenReturn(List.of(
                Unit.restore(1L, "瓶", 0, "t", Instant.now(), "t", Instant.now()),
                Unit.restore(2L, "箱", 0, "t", Instant.now(), "t", Instant.now())));
    }

    @Test
    void 按id查询_含换算关系() {
        when(productService.get(7L)).thenReturn(product);

        ToolResult result = tool.execute(Map.of("id", 7), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("code", "SKU-202606-0007")
                .containsEntry("baseUnit", "瓶")
                .containsEntry("status", "启用");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conversions = (List<Map<String, Object>>) result.data().get("unitConversions");
        assertThat(conversions).hasSize(1);
        assertThat(conversions.get(0))
                .containsEntry("unit", "箱")
                .containsEntry("rate", "12") // 精度原则：字符串承载
                .containsEntry("meaning", "1 箱 = 12 瓶");
    }

    @Test
    void 按编码精确匹配() {
        when(productService.search(eq(new ProductQuery("SKU-202606-0007", null, 1, ProductQuery.MAX_SIZE))))
                .thenReturn(new PageResult<>(List.of(product), 1, 1, ProductQuery.MAX_SIZE));

        ToolResult result = tool.execute(Map.of("code", "SKU-202606-0007"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("name", "矿泉水");
    }

    @Test
    void 编码模糊命中但无精确匹配_视为未找到() {
        when(productService.search(eq(new ProductQuery("SKU-2026", null, 1, ProductQuery.MAX_SIZE))))
                .thenReturn(new PageResult<>(List.of(product), 1, 1, ProductQuery.MAX_SIZE));

        ToolResult result = tool.execute(Map.of("code", "SKU-2026"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("未找到");
    }

    @Test
    void id与code都缺_拒绝() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("至少传一个");
    }

    @Test
    void id不存在_失败回灌() {
        when(productService.get(anyLong())).thenThrow(CatalogNotFoundException.product(99L));

        ToolResult result = tool.execute(Map.of("id", 99), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("99");
    }
}
