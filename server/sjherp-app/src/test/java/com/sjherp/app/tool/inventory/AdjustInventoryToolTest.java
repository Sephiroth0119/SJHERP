package com.sjherp.app.tool.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.inventory.InventoryTxnType;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * adjust_inventory 工具单测（M3-T01c）：风险级别与权限点声明、期初/成本调整命令映射、
 * 操作人 agent:&lt;userId&gt;、参数缺失与名称解析失败（不触碰写入口）、领域拒绝转 fail、
 * Schema 必填/枚举校验。
 */
class AdjustInventoryToolTest {

    private WarehouseService warehouseService;
    private ProductService productService;
    private InventoryAdjustmentService adjustmentService;
    private AdjustInventoryTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "期初建账");

    @BeforeEach
    void setUp() {
        warehouseService = mock(WarehouseService.class);
        productService = mock(ProductService.class);
        adjustmentService = mock(InventoryAdjustmentService.class);
        tool = new AdjustInventoryTool(warehouseService, productService, adjustmentService);
    }

    private static Warehouse warehouse() {
        return Warehouse.restore(1L, "WH-202606-0001", "一号仓", null, null, false,
                ArchiveStatus.ENABLED, "t", Instant.now(), "t", Instant.now());
    }

    private static Product product() {
        return Product.restore(2L, "SKU-202606-0001", "不锈钢板 304L", null, null, 1L, null,
                ArchiveStatus.ENABLED, null, List.of(), "t", Instant.now(), "t", Instant.now());
    }

    private static <T> PageResult<T> page(List<T> items) {
        return new PageResult<>(items, items.size(), 1, 10);
    }

    private void stubResolution() {
        when(warehouseService.search(eq(new WarehouseQuery("一号仓", null, 1, 10))))
                .thenReturn(page(List.of(warehouse())));
        when(productService.search(eq(new ProductQuery("不锈钢板 304L", null, 1, 10))))
                .thenReturn(page(List.of(product())));
    }

    private static StockMovementResult openingResult() {
        return new StockMovementResult(9L, 1L, 2L, InventoryTxnType.OPENING,
                new BigDecimal("100.000000"), new BigDecimal("10.000000"), new BigDecimal("1000.00"),
                new BigDecimal("100.000000"), new BigDecimal("1000.00"),
                "OPENING", "OP-202606-0001", 1, "OPENING:OP-202606-0001:1");
    }

    @Test
    void 风险级别HIGH_权限点inventory_adjust() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("inventory:adjust");
    }

    @Test
    void 期初建账_命令映射_操作人记agent前缀() {
        stubResolution();
        when(adjustmentService.opening(eq(1L), eq(2L), eq(new BigDecimal("100")),
                eq(new BigDecimal("10.00")), eq("agent:1"))).thenReturn(openingResult());

        ToolResult result = tool.execute(Map.of(
                "type", "OPENING", "warehouse", "一号仓", "product", "不锈钢板 304L",
                "quantity", "100", "unit_cost", "10.00"), context);

        assertThat(result.success()).isTrue();
        verify(adjustmentService).opening(eq(1L), eq(2L), eq(new BigDecimal("100")),
                eq(new BigDecimal("10.00")), eq("agent:1"));
        assertThat(result.data())
                .containsEntry("docNo", "OP-202606-0001")
                .containsEntry("txnType", "期初")
                .containsEntry("balanceQuantityAfter", "100.000000")
                .containsEntry("balanceAmountAfter", "1000.00");
    }

    @Test
    void 成本调整_负调整额映射() {
        stubResolution();
        when(adjustmentService.costAdjust(eq(1L), eq(2L), eq(new BigDecimal("-5.00")), eq("agent:1")))
                .thenReturn(new StockMovementResult(10L, 1L, 2L, InventoryTxnType.COST_ADJUST,
                        new BigDecimal("0.000000"), null, new BigDecimal("-5.00"),
                        new BigDecimal("100.000000"), new BigDecimal("995.00"),
                        "COST_ADJUST", "CA-202606-0001", 1, "COST_ADJUST:CA-202606-0001:1"));

        ToolResult result = tool.execute(Map.of(
                "type", "COST_ADJUST", "warehouse", "一号仓", "product", "不锈钢板 304L",
                "adjust_amount", "-5.00"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("docNo", "CA-202606-0001")
                .containsEntry("totalCost", "-5.00");
        assertThat(result.data().get("unitCost")).isNull();
    }

    @Test
    void 期初建账缺数量或单价_失败且不触碰写入口() {
        stubResolution();

        ToolResult result = tool.execute(Map.of(
                "type", "OPENING", "warehouse", "一号仓", "product", "不锈钢板 304L",
                "unit_cost", "10.00"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("quantity").contains("unit_cost");
        verifyNoInteractions(adjustmentService);
    }

    @Test
    void 成本调整缺调整额_失败且不触碰写入口() {
        stubResolution();

        ToolResult result = tool.execute(Map.of(
                "type", "COST_ADJUST", "warehouse", "一号仓", "product", "不锈钢板 304L"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("adjust_amount");
        verifyNoInteractions(adjustmentService);
    }

    @Test
    void 类型非法_失败() {
        ToolResult result = tool.execute(Map.of(
                "type", "SALES_OUT", "warehouse", "一号仓", "product", "不锈钢板 304L"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("仅支持 OPENING / COST_ADJUST");
        verifyNoInteractions(adjustmentService, warehouseService, productService);
    }

    @Test
    void 商品解析失败_列候选_不触碰写入口() {
        when(warehouseService.search(eq(new WarehouseQuery("一号仓", null, 1, 10))))
                .thenReturn(page(List.of(warehouse())));
        when(productService.search(eq(new ProductQuery("钢板", null, 1, 10))))
                .thenReturn(page(List.of(
                        Product.restore(2L, "SKU-202606-0001", "不锈钢板 304L", null, null, 1L, null,
                                ArchiveStatus.ENABLED, null, List.of(), "t", Instant.now(), "t", Instant.now()),
                        Product.restore(3L, "SKU-202606-0002", "不锈钢板 316L", null, null, 1L, null,
                                ArchiveStatus.ENABLED, null, List.of(), "t", Instant.now(), "t", Instant.now()))));

        ToolResult result = tool.execute(Map.of(
                "type", "OPENING", "warehouse", "一号仓", "product", "钢板",
                "quantity", "100", "unit_cost", "10.00"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("匹配到多个商品").contains("SKU-202606-0001");
        verifyNoInteractions(adjustmentService);
    }

    @Test
    void 数值格式非法_失败且不触碰写入口() {
        stubResolution();

        ToolResult result = tool.execute(Map.of(
                "type", "OPENING", "warehouse", "一号仓", "product", "不锈钢板 304L",
                "quantity", "一百", "unit_cost", "10.00"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("数值格式不合法");
        verifyNoInteractions(adjustmentService);
    }

    @Test
    void 领域校验拒绝转失败结果() {
        stubResolution();
        when(adjustmentService.opening(eq(1L), eq(2L), eq(new BigDecimal("100")),
                eq(new BigDecimal("10.00")), eq("agent:1")))
                .thenThrow(new IllegalArgumentException("商品已停用，禁止库存调整: 不锈钢板 304L"));

        ToolResult result = tool.execute(Map.of(
                "type", "OPENING", "warehouse", "一号仓", "product", "不锈钢板 304L",
                "quantity", "100", "unit_cost", "10.00"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("库存调整被拒绝").contains("已停用");
    }

    @Test
    void schema校验_缺type拒绝_枚举约束() {
        JsonSchemaToolArgumentValidator validator = new JsonSchemaToolArgumentValidator();
        List<String> missing = validator.validate(tool.parameterSchema(),
                Map.of("warehouse", "一号仓", "product", "钢板"));
        assertThat(missing).anyMatch(e -> e.contains("type"));

        List<String> badEnum = validator.validate(tool.parameterSchema(),
                Map.of("type", "PURCHASE_IN", "warehouse", "一号仓", "product", "钢板"));
        assertThat(badEnum).isNotEmpty();
    }
}
