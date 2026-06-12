package com.sjherp.app.tool.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * query_inventory_balance 工具单测（M3-T01c）：名称/编码解析（精确优先、歧义列候选、
 * 未命中列全量候选）、余额字段字符串承载、零余额提示、Schema 必填校验。
 */
class QueryInventoryBalanceToolTest {

    private WarehouseService warehouseService;
    private ProductService productService;
    private TransactionalInventoryService inventoryService;
    private QueryInventoryBalanceTool tool;
    private final ToolContext context = new ToolContext("session-1", "1", "查库存");

    @BeforeEach
    void setUp() {
        warehouseService = mock(WarehouseService.class);
        productService = mock(ProductService.class);
        inventoryService = mock(TransactionalInventoryService.class);
        tool = new QueryInventoryBalanceTool(warehouseService, productService, inventoryService);
    }

    private static Warehouse warehouse(long id, String code, String name) {
        return Warehouse.restore(id, code, name, null, null, false, ArchiveStatus.ENABLED,
                "t", Instant.now(), "t", Instant.now());
    }

    private static Product product(long id, String code, String name) {
        return Product.restore(id, code, name, null, null, 1L, null, ArchiveStatus.ENABLED,
                null, List.of(), "t", Instant.now(), "t", Instant.now());
    }

    private static <T> PageResult<T> page(List<T> items) {
        return new PageResult<>(items, items.size(), 1, 10);
    }

    @Test
    void 风险级别为NORMAL_无权限点() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isNull();
    }

    @Test
    void 按名称解析仓库与商品_返回余额字符串与派生单价() {
        when(warehouseService.search(eq(new WarehouseQuery("一号仓", null, 1, 10))))
                .thenReturn(page(List.of(warehouse(1L, "WH-202606-0001", "一号仓"))));
        when(productService.search(eq(new ProductQuery("不锈钢板 304L", null, 1, 10))))
                .thenReturn(page(List.of(product(2L, "SKU-202606-0001", "不锈钢板 304L"))));
        when(inventoryService.balanceOf(1L, 2L)).thenReturn(new InventoryBalanceView(1L, 2L,
                new BigDecimal("100.000000"), new BigDecimal("1000.00")));

        ToolResult result = tool.execute(
                Map.of("warehouse", "一号仓", "product", "不锈钢板 304L"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("warehouse", "一号仓")
                .containsEntry("product", "不锈钢板 304L")
                .containsEntry("quantity", "100.000000")
                .containsEntry("costAmount", "1000.00")
                .containsEntry("unitCost", "10.000000");
    }

    @Test
    void 零余额_附说明且单价为空() {
        when(warehouseService.search(eq(new WarehouseQuery("一号仓", null, 1, 10))))
                .thenReturn(page(List.of(warehouse(1L, "WH-202606-0001", "一号仓"))));
        when(productService.search(eq(new ProductQuery("螺丝", null, 1, 10))))
                .thenReturn(page(List.of(product(3L, "SKU-202606-0002", "螺丝"))));
        when(inventoryService.balanceOf(1L, 3L))
                .thenReturn(InventoryBalanceView.empty(1L, 3L));

        ToolResult result = tool.execute(Map.of("warehouse", "一号仓", "product", "螺丝"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("quantity", "0.000000");
        assertThat(result.data().get("unitCost")).isNull();
        assertThat(String.valueOf(result.data().get("note"))).contains("结存为零");
    }

    @Test
    void 商品名称歧义_失败并列出候选_不查询余额() {
        when(warehouseService.search(eq(new WarehouseQuery("一号仓", null, 1, 10))))
                .thenReturn(page(List.of(warehouse(1L, "WH-202606-0001", "一号仓"))));
        when(productService.search(eq(new ProductQuery("钢板", null, 1, 10))))
                .thenReturn(page(List.of(
                        product(2L, "SKU-202606-0001", "不锈钢板 304L"),
                        product(3L, "SKU-202606-0002", "不锈钢板 316L"))));

        ToolResult result = tool.execute(Map.of("warehouse", "一号仓", "product", "钢板"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("匹配到多个商品")
                .contains("不锈钢板 304L（SKU-202606-0001）")
                .contains("不锈钢板 316L（SKU-202606-0002）");
        verifyNoInteractions(inventoryService);
    }

    @Test
    void 多条模糊命中但名称精确匹配唯一_直接命中() {
        when(warehouseService.search(eq(new WarehouseQuery("一号仓", null, 1, 10))))
                .thenReturn(page(List.of(
                        warehouse(1L, "WH-202606-0001", "一号仓"),
                        warehouse(2L, "WH-202606-0002", "一号仓退货区"))));
        when(productService.search(eq(new ProductQuery("螺丝", null, 1, 10))))
                .thenReturn(page(List.of(product(3L, "SKU-202606-0002", "螺丝"))));
        when(inventoryService.balanceOf(1L, 3L))
                .thenReturn(InventoryBalanceView.empty(1L, 3L));

        ToolResult result = tool.execute(Map.of("warehouse", "一号仓", "product", "螺丝"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("warehouseCode", "WH-202606-0001");
    }

    @Test
    void 仓库未命中_失败并列出系统内候选() {
        when(warehouseService.search(eq(new WarehouseQuery("不存在的仓", null, 1, 10))))
                .thenReturn(page(List.of()));
        when(warehouseService.search(eq(new WarehouseQuery(null, null, 1, 10))))
                .thenReturn(page(List.of(warehouse(1L, "WH-202606-0001", "一号仓"))));

        ToolResult result = tool.execute(
                Map.of("warehouse", "不存在的仓", "product", "螺丝"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("未找到名称或编码匹配「不存在的仓」的仓库")
                .contains("一号仓（WH-202606-0001）");
        verifyNoInteractions(inventoryService);
    }

    @Test
    void schema校验_缺仓库或商品被拒() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("warehouse", "一号仓"));
        assertThat(errors).anyMatch(e -> e.contains("product"));
    }
}
