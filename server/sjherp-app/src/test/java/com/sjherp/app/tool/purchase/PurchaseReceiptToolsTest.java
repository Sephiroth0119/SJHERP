package com.sjherp.app.tool.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptNotFoundException;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.domain.common.PageResult;

/**
 * 采购入库单工具单测（M3-T11）：CreatePurchaseReceiptTool（create_purchase_receipt）、
 * ApprovePurchaseReceiptTool（approve_purchase_receipt）、
 * PostPurchaseReceiptTool（post_purchase_receipt）、
 * QueryPurchaseReceiptTool（query_purchase_receipt）覆盖
 * 风险级别、权限点、必填校验、operator 前缀 agent:、AppService 调用、领域拒绝转 fail。
 */
class PurchaseReceiptToolsTest {

    private WarehouseService warehouseService;
    private PurchaseReceiptAppService purchaseReceiptAppService;
    private final ToolContext context = new ToolContext("session-1", "7", "采购入库");

    @BeforeEach
    void setUp() {
        warehouseService = mock(WarehouseService.class);
        purchaseReceiptAppService = mock(PurchaseReceiptAppService.class);
    }

    private static Warehouse warehouse() {
        return Warehouse.restore(1L, "WH-202606-0001", "一号仓", null, null, false,
                ArchiveStatus.ENABLED, "t", Instant.now(), "t", Instant.now());
    }

    private static <T> PageResult<T> page(List<T> items) {
        return new PageResult<>(items, items.size(), 1, 10);
    }

    // ======= CreatePurchaseReceiptTool =======

    @Test
    void create_风险级别HIGH_权限点purchase_receipt() {
        CreatePurchaseReceiptTool tool = new CreatePurchaseReceiptTool(
                warehouseService, purchaseReceiptAppService);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("purchase:receipt");
    }

    @Test
    void create_缺purchase_order_no_失败且不触碰AppService() {
        CreatePurchaseReceiptTool tool = new CreatePurchaseReceiptTool(
                warehouseService, purchaseReceiptAppService);
        when(warehouseService.search(any())).thenReturn(page(List.of(warehouse())));

        ToolResult result = tool.execute(Map.of(
                "warehouse", "一号仓",
                "lines", List.of(Map.of("po_line_no", 1, "quantity", "100"))), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("purchase_order_no");
        verifyNoInteractions(purchaseReceiptAppService);
    }

    @Test
    void create_仓库解析失败_不触碰AppService() {
        CreatePurchaseReceiptTool tool = new CreatePurchaseReceiptTool(
                warehouseService, purchaseReceiptAppService);
        when(warehouseService.search(any())).thenReturn(page(List.of()));

        ToolResult result = tool.execute(Map.of(
                "purchase_order_no", "PO-202606-0001",
                "warehouse", "不存在仓库",
                "lines", List.of(Map.of("po_line_no", 1, "quantity", "100"))), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("仓库").contains("解析失败");
        verifyNoInteractions(purchaseReceiptAppService);
    }

    @Test
    void create_lines为空_失败() {
        CreatePurchaseReceiptTool tool = new CreatePurchaseReceiptTool(
                warehouseService, purchaseReceiptAppService);
        when(warehouseService.search(any())).thenReturn(page(List.of(warehouse())));

        ToolResult result = tool.execute(Map.of(
                "purchase_order_no", "PO-202606-0001",
                "warehouse", "一号仓",
                "lines", List.of()), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("lines");
        verifyNoInteractions(purchaseReceiptAppService);
    }

    @Test
    void create_正常建单_operator记agent前缀_返回成功() {
        CreatePurchaseReceiptTool tool = new CreatePurchaseReceiptTool(
                warehouseService, purchaseReceiptAppService);
        when(warehouseService.search(eq(new WarehouseQuery("一号仓", null, 1, 10))))
                .thenReturn(page(List.of(warehouse())));
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(receipt.getPurchaseOrderNo()).thenReturn("PO-202606-0001");
        when(receipt.getReceiptDate()).thenReturn(java.time.LocalDate.of(2026, 6, 13));
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(receipt.getLines()).thenReturn(List.of());
        when(purchaseReceiptAppService.create(
                eq("PO-202606-0001"), eq(1L), any(), any(), any(), eq("agent:7")))
                .thenReturn(receipt);

        ToolResult result = tool.execute(Map.of(
                "purchase_order_no", "PO-202606-0001",
                "warehouse", "一号仓",
                "lines", List.of(Map.of("po_line_no", 1, "quantity", "100", "unit_cost", "18.00"))),
                context);

        assertThat(result.success()).isTrue();
        verify(purchaseReceiptAppService).create(
                eq("PO-202606-0001"), eq(1L), any(), any(), any(), eq("agent:7"));
        assertThat(result.data()).containsEntry("docNo", "PR-202606-0001");
    }

    @Test
    void create_领域拒绝_转fail() {
        CreatePurchaseReceiptTool tool = new CreatePurchaseReceiptTool(
                warehouseService, purchaseReceiptAppService);
        when(warehouseService.search(any())).thenReturn(page(List.of(warehouse())));
        when(purchaseReceiptAppService.create(any(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("采购订单未审核，不可收货"));

        ToolResult result = tool.execute(Map.of(
                "purchase_order_no", "PO-202606-DRAFT",
                "warehouse", "一号仓",
                "lines", List.of(Map.of("po_line_no", 1, "quantity", "50"))), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("被拒绝");
    }

    // ======= ApprovePurchaseReceiptTool =======

    @Test
    void approve_风险级别HIGH_权限点purchase_receipt() {
        ApprovePurchaseReceiptTool tool = new ApprovePurchaseReceiptTool(purchaseReceiptAppService);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("purchase:receipt");
    }

    @Test
    void approve_doc_no缺失_失败且不触碰AppService() {
        ApprovePurchaseReceiptTool tool = new ApprovePurchaseReceiptTool(purchaseReceiptAppService);

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(purchaseReceiptAppService);
    }

    @Test
    void approve_正常审核_operator记agent前缀_返回成功() {
        ApprovePurchaseReceiptTool tool = new ApprovePurchaseReceiptTool(purchaseReceiptAppService);
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.getStatus()).thenReturn(DocumentStatus.APPROVED);
        when(receipt.getPurchaseOrderNo()).thenReturn("PO-202606-0001");
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(purchaseReceiptAppService.approve(eq("PR-202606-0001"), eq("agent:7")))
                .thenReturn(receipt);

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(purchaseReceiptAppService).approve(eq("PR-202606-0001"), eq("agent:7"));
        assertThat(result.data()).containsEntry("docNo", "PR-202606-0001");
    }

    @Test
    void approve_单据不存在_转fail() {
        ApprovePurchaseReceiptTool tool = new ApprovePurchaseReceiptTool(purchaseReceiptAppService);
        when(purchaseReceiptAppService.approve(eq("PR-NOT-EXIST"), eq("agent:7")))
                .thenThrow(new PurchaseReceiptNotFoundException("PR-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "PR-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void approve_状态流转拒绝_转fail() {
        ApprovePurchaseReceiptTool tool = new ApprovePurchaseReceiptTool(purchaseReceiptAppService);
        when(purchaseReceiptAppService.approve(eq("PR-202606-0001"), eq("agent:7")))
                .thenThrow(new IllegalStateTransitionException("PR-202606-0001",
                        DocumentStatus.APPROVED, DocumentStatus.APPROVED));

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("被拒绝");
    }

    // ======= PostPurchaseReceiptTool =======

    @Test
    void post_风险级别HIGH_权限点purchase_receipt() {
        PostPurchaseReceiptTool tool = new PostPurchaseReceiptTool(purchaseReceiptAppService);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("purchase:receipt");
    }

    @Test
    void post_doc_no缺失_失败且不触碰AppService() {
        PostPurchaseReceiptTool tool = new PostPurchaseReceiptTool(purchaseReceiptAppService);

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(purchaseReceiptAppService);
    }

    @Test
    void post_正常过账_operator记agent前缀_返回成功() {
        PostPurchaseReceiptTool tool = new PostPurchaseReceiptTool(purchaseReceiptAppService);
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(purchaseReceiptAppService.post(eq("PR-202606-0001"), eq("agent:7")))
                .thenReturn(receipt);

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        verify(purchaseReceiptAppService).post(eq("PR-202606-0001"), eq("agent:7"));
        assertThat(result.data()).containsEntry("docNo", "PR-202606-0001");
    }

    @Test
    void post_单据不存在_转fail() {
        PostPurchaseReceiptTool tool = new PostPurchaseReceiptTool(purchaseReceiptAppService);
        when(purchaseReceiptAppService.post(eq("PR-NOT-EXIST"), eq("agent:7")))
                .thenThrow(new PurchaseReceiptNotFoundException("PR-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "PR-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void post_状态流转拒绝_转fail() {
        PostPurchaseReceiptTool tool = new PostPurchaseReceiptTool(purchaseReceiptAppService);
        when(purchaseReceiptAppService.post(eq("PR-202606-0001"), eq("agent:7")))
                .thenThrow(new IllegalStateTransitionException("PR-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.COMPLETED));

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("被拒绝");
    }

    // ======= QueryPurchaseReceiptTool =======

    @Test
    void query_风险级别NORMAL_无权限点() {
        QueryPurchaseReceiptTool tool = new QueryPurchaseReceiptTool(purchaseReceiptAppService);
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isNull();
    }

    @Test
    void query_doc_no缺失_失败() {
        QueryPurchaseReceiptTool tool = new QueryPurchaseReceiptTool(purchaseReceiptAppService);

        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
    }

    @Test
    void query_单据存在_返回数据() {
        QueryPurchaseReceiptTool tool = new QueryPurchaseReceiptTool(purchaseReceiptAppService);
        PurchaseReceipt receipt = mock(PurchaseReceipt.class);
        when(receipt.getDocNo()).thenReturn("PR-202606-0001");
        when(receipt.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(receipt.getPurchaseOrderNo()).thenReturn("PO-202606-0001");
        when(receipt.getWarehouseId()).thenReturn(1L);
        when(receipt.getReceiptDate()).thenReturn(java.time.LocalDate.of(2026, 6, 13));
        when(receipt.getRemark()).thenReturn(null);
        when(receipt.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(receipt.getLines()).thenReturn(List.of());
        when(purchaseReceiptAppService.get(eq("PR-202606-0001"))).thenReturn(receipt);

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data())
                .containsEntry("docNo", "PR-202606-0001")
                .containsEntry("purchaseOrderNo", "PO-202606-0001");
    }

    @Test
    void query_单据不存在_转fail() {
        QueryPurchaseReceiptTool tool = new QueryPurchaseReceiptTool(purchaseReceiptAppService);
        when(purchaseReceiptAppService.get(eq("PR-NOT-EXIST")))
                .thenThrow(new PurchaseReceiptNotFoundException("PR-NOT-EXIST"));

        ToolResult result = tool.execute(Map.of("doc_no", "PR-NOT-EXIST"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }
}
