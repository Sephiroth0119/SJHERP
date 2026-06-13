package com.sjherp.app.tool.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.sales.SalesDeliveryNotFoundException;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 销售出库单工具组单测（M3-T11）：create/approve/post/query_sales_delivery
 * 的风险级别/权限点、多行解析、仓库/商品名称解析、operator 前缀、AppService verify、错误路径。
 */
class SalesDeliveryToolsTest {

    private WarehouseService warehouseService;
    private ProductService productService;
    private SalesDeliveryAppService salesDeliveryAppService;
    private CreateSalesDeliveryTool createTool;
    private ApproveSalesDeliveryTool approveTool;
    private PostSalesDeliveryTool postTool;
    private QuerySalesDeliveryTool queryTool;
    private final ToolContext context = new ToolContext("session-4", "11", "操作销售出库单");

    @BeforeEach
    void setUp() {
        warehouseService = mock(WarehouseService.class);
        productService = mock(ProductService.class);
        salesDeliveryAppService = mock(SalesDeliveryAppService.class);
        createTool = new CreateSalesDeliveryTool(warehouseService, productService, salesDeliveryAppService);
        approveTool = new ApproveSalesDeliveryTool(salesDeliveryAppService);
        postTool = new PostSalesDeliveryTool(salesDeliveryAppService);
        queryTool = new QuerySalesDeliveryTool(salesDeliveryAppService);
    }

    private static Warehouse warehouse() {
        return Warehouse.restore(20L, "WH-202606-0002", "二号仓", null, null, false,
                ArchiveStatus.ENABLED, "t", Instant.now(), "t", Instant.now());
    }

    private static Product product() {
        return Product.restore(30L, "PROD-202606-0001", "测试商品", null, null, 1L, null,
                ArchiveStatus.ENABLED, null, List.of(), "t", Instant.now(), "t", Instant.now());
    }

    private static <T> PageResult<T> page(List<T> items) {
        return new PageResult<>(items, items.size(), 1, 10);
    }

    // ------------------------------------------------------------------ create

    @Test
    void create_风险级别HIGH_权限点sales_delivery() {
        assertThat(createTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(createTool.requiredPermission()).isEqualTo("sales:delivery");
    }

    @Test
    void create_sales_order_no缺失_失败且不触碰AppService() {
        ToolResult result = createTool.execute(Map.of(
                "warehouse", "二号仓",
                "lines", List.of(Map.of("so_line_no", 1, "product", "测试商品", "quantity", "50"))),
                context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("sales_order_no");
        verifyNoInteractions(salesDeliveryAppService);
    }

    @Test
    void create_仓库解析失败_不触碰AppService() {
        when(warehouseService.search(any(WarehouseQuery.class))).thenReturn(page(List.of()));
        ToolResult result = createTool.execute(Map.of(
                "sales_order_no", "SO-202606-0001",
                "warehouse", "不存在仓库",
                "lines", List.of(Map.of("so_line_no", 1, "product", "测试商品", "quantity", "50"))),
                context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("仓库");
        verifyNoInteractions(salesDeliveryAppService);
    }

    @Test
    void create_商品解析失败_不触碰AppService() {
        when(warehouseService.search(any(WarehouseQuery.class))).thenReturn(page(List.of(warehouse())));
        when(productService.search(any(ProductQuery.class))).thenReturn(page(List.of()));
        ToolResult result = createTool.execute(Map.of(
                "sales_order_no", "SO-202606-0001",
                "warehouse", "二号仓",
                "lines", List.of(Map.of("so_line_no", 1, "product", "不存在商品", "quantity", "50"))),
                context);
        assertThat(result.success()).isFalse();
        verifyNoInteractions(salesDeliveryAppService);
    }

    @Test
    void create_正常多行解析_verify() {
        when(warehouseService.search(any(WarehouseQuery.class))).thenReturn(page(List.of(warehouse())));
        when(productService.search(any(ProductQuery.class))).thenReturn(page(List.of(product())));

        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.getDocNo()).thenReturn("SD-202606-0001");
        when(delivery.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(delivery.getSalesOrderNo()).thenReturn("SO-202606-0001");
        when(delivery.getWarehouseId()).thenReturn(20L);
        when(delivery.getLines()).thenReturn(List.of());
        when(salesDeliveryAppService.create(
                eq("SO-202606-0001"), eq(20L), isNull(), any(), eq("agent:11")))
                .thenReturn(delivery);

        ToolResult result = createTool.execute(Map.of(
                "sales_order_no", "SO-202606-0001",
                "warehouse", "二号仓",
                "lines", List.of(
                        Map.of("so_line_no", 1, "product", "测试商品", "quantity", "50")
                )), context);

        assertThat(result.success()).isTrue();
        verify(salesDeliveryAppService).create(
                eq("SO-202606-0001"), eq(20L), isNull(), any(), eq("agent:11"));
    }

    // ------------------------------------------------------------------ approve

    @Test
    void approve_风险级别HIGH_权限点sales_delivery() {
        assertThat(approveTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(approveTool.requiredPermission()).isEqualTo("sales:delivery");
    }

    @Test
    void approve_doc_no缺失_失败() {
        ToolResult result = approveTool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        verifyNoInteractions(salesDeliveryAppService);
    }

    @Test
    void approve_正常调用_verify() {
        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.getDocNo()).thenReturn("SD-202606-0001");
        when(delivery.getStatus()).thenReturn(DocumentStatus.APPROVED);
        when(salesDeliveryAppService.approve(eq("SD-202606-0001"), eq("agent:11")))
                .thenReturn(delivery);

        ToolResult result = approveTool.execute(Map.of("doc_no", "SD-202606-0001"), context);
        assertThat(result.success()).isTrue();
        verify(salesDeliveryAppService).approve(eq("SD-202606-0001"), eq("agent:11"));
    }

    @Test
    void approve_领域拒绝_转fail() {
        when(salesDeliveryAppService.approve(any(), any()))
                .thenThrow(new IllegalStateTransitionException("SD-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.COMPLETED));
        ToolResult result = approveTool.execute(Map.of("doc_no", "SD-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("状态流转");
    }

    // ------------------------------------------------------------------ post

    @Test
    void post_风险级别HIGH_权限点sales_delivery() {
        assertThat(postTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(postTool.requiredPermission()).isEqualTo("sales:delivery");
    }

    @Test
    void post_正常调用_含COGS() {
        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.getDocNo()).thenReturn("SD-202606-0001");
        when(delivery.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(delivery.getSalesOrderNo()).thenReturn("SO-202606-0001");
        when(delivery.totalCogs()).thenReturn(new BigDecimal("900.00"));
        when(salesDeliveryAppService.post(eq("SD-202606-0001"), eq("agent:11")))
                .thenReturn(delivery);

        ToolResult result = postTool.execute(Map.of("doc_no", "SD-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("totalCogs", "900.00");
    }

    @Test
    void post_库存不足_转fail() {
        when(salesDeliveryAppService.post(any(), any()))
                .thenThrow(new InsufficientStockException(1L, 2L, BigDecimal.ZERO, new BigDecimal("10")));
        ToolResult result = postTool.execute(Map.of("doc_no", "SD-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("库存不足");
    }

    // ------------------------------------------------------------------ query

    @Test
    void query_风险级别NORMAL_权限点null() {
        assertThat(queryTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(queryTool.requiredPermission()).isNull();
    }

    @Test
    void query_doc_no缺失_失败() {
        ToolResult result = queryTool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        verifyNoInteractions(salesDeliveryAppService);
    }

    @Test
    void query_单据不存在_转fail() {
        when(salesDeliveryAppService.get("SD-NOT-EXIST"))
                .thenThrow(new SalesDeliveryNotFoundException("SD-NOT-EXIST"));
        ToolResult result = queryTool.execute(Map.of("doc_no", "SD-NOT-EXIST"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void query_正常查询_返回头行字段() {
        SalesDelivery delivery = mock(SalesDelivery.class);
        when(delivery.getDocNo()).thenReturn("SD-202606-0001");
        when(delivery.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(delivery.getSalesOrderNo()).thenReturn("SO-202606-0001");
        when(delivery.getWarehouseId()).thenReturn(20L);
        when(delivery.getRemark()).thenReturn(null);
        when(delivery.totalCogs()).thenReturn(new BigDecimal("900.00"));
        when(delivery.getLines()).thenReturn(List.of());
        when(salesDeliveryAppService.get("SD-202606-0001")).thenReturn(delivery);

        ToolResult result = queryTool.execute(Map.of("doc_no", "SD-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "SD-202606-0001");
        assertThat(result.data()).containsEntry("salesOrderNo", "SO-202606-0001");
        assertThat(result.data()).containsKey("lines");
    }
}
