package com.sjherp.app.tool.purchase;

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
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierQuery;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.PayableStatus;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceNotFoundException;

/**
 * 采购发票工具组单测（M3-T11）：create/approve/post/query_purchase_invoice + query_payables
 * 的风险级别/权限点、多行解析、operator 前缀、AppService verify、错误路径转 fail。
 */
class PurchaseInvoiceToolsTest {

    private PurchaseInvoiceAppService purchaseInvoiceAppService;
    private SupplierService supplierService;
    private CreatePurchaseInvoiceTool createTool;
    private ApprovePurchaseInvoiceTool approveTool;
    private PostPurchaseInvoiceTool postTool;
    private QueryPurchaseInvoiceTool queryTool;
    private QueryPayablesTool queryPayablesTool;
    private final ToolContext context = new ToolContext("session-2", "5", "操作采购发票");

    @BeforeEach
    void setUp() {
        purchaseInvoiceAppService = mock(PurchaseInvoiceAppService.class);
        supplierService = mock(SupplierService.class);
        createTool = new CreatePurchaseInvoiceTool(purchaseInvoiceAppService);
        approveTool = new ApprovePurchaseInvoiceTool(purchaseInvoiceAppService);
        postTool = new PostPurchaseInvoiceTool(purchaseInvoiceAppService);
        queryTool = new QueryPurchaseInvoiceTool(purchaseInvoiceAppService);
        queryPayablesTool = new QueryPayablesTool(supplierService, purchaseInvoiceAppService);
    }

    private static <T> PageResult<T> page(List<T> items) {
        return new PageResult<>(items, items.size(), 1, 10);
    }

    // ------------------------------------------------------------------ create

    @Test
    void create_风险级别HIGH_权限点purchase_invoice() {
        assertThat(createTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(createTool.requiredPermission()).isEqualTo("purchase:invoice");
    }

    @Test
    void create_purchase_receipt_no缺失_失败() {
        ToolResult result = createTool.execute(Map.of(
                "lines", List.of(Map.of("receipt_line_no", 1, "quantity", "100", "amount", "1800.00"))),
                context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("purchase_receipt_no");
        verifyNoInteractions(purchaseInvoiceAppService);
    }

    @Test
    void create_lines为空_失败() {
        ToolResult result = createTool.execute(Map.of(
                "purchase_receipt_no", "PR-202606-0001", "lines", List.of()), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("lines");
        verifyNoInteractions(purchaseInvoiceAppService);
    }

    @Test
    void create_正常多行调用verify() {
        PurchaseInvoice invoice = mock(PurchaseInvoice.class);
        when(invoice.getDocNo()).thenReturn("PINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(invoice.getPurchaseReceiptNo()).thenReturn("PR-202606-0001");
        when(invoice.getSupplierId()).thenReturn(1L);
        when(invoice.getInvoiceDate()).thenReturn(java.time.LocalDate.now());
        when(invoice.getSupplierInvoiceNo()).thenReturn(null);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("3600.00"));
        when(invoice.getLines()).thenReturn(List.of());
        when(purchaseInvoiceAppService.create(
                eq("PR-202606-0001"), isNull(), isNull(), isNull(), any(), eq("agent:5")))
                .thenReturn(invoice);

        ToolResult result = createTool.execute(Map.of(
                "purchase_receipt_no", "PR-202606-0001",
                "lines", List.of(
                        Map.of("receipt_line_no", 1, "quantity", "100", "amount", "1800.00"),
                        Map.of("receipt_line_no", 2, "quantity", "50", "amount", "1800.00")
                )), context);

        assertThat(result.success()).isTrue();
        verify(purchaseInvoiceAppService).create(
                eq("PR-202606-0001"), isNull(), isNull(), isNull(), any(), eq("agent:5"));
    }

    // ------------------------------------------------------------------ approve

    @Test
    void approve_风险级别HIGH_权限点purchase_invoice() {
        assertThat(approveTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(approveTool.requiredPermission()).isEqualTo("purchase:invoice");
    }

    @Test
    void approve_doc_no缺失_失败() {
        ToolResult result = approveTool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        verifyNoInteractions(purchaseInvoiceAppService);
    }

    @Test
    void approve_正常调用_verify() {
        PurchaseInvoice invoice = mock(PurchaseInvoice.class);
        when(invoice.getDocNo()).thenReturn("PINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.APPROVED);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(purchaseInvoiceAppService.approve(eq("PINV-202606-0001"), eq("agent:5")))
                .thenReturn(invoice);

        ToolResult result = approveTool.execute(Map.of("doc_no", "PINV-202606-0001"), context);
        assertThat(result.success()).isTrue();
        verify(purchaseInvoiceAppService).approve(eq("PINV-202606-0001"), eq("agent:5"));
    }

    @Test
    void approve_领域拒绝_转fail() {
        when(purchaseInvoiceAppService.approve(any(), any()))
                .thenThrow(new IllegalStateTransitionException("PINV-202606-0001",
                        DocumentStatus.COMPLETED, DocumentStatus.APPROVED));
        ToolResult result = approveTool.execute(Map.of("doc_no", "PINV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("被拒绝");
    }

    // ------------------------------------------------------------------ post

    @Test
    void post_风险级别HIGH_权限点purchase_invoice() {
        assertThat(postTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(postTool.requiredPermission()).isEqualTo("purchase:invoice");
    }

    @Test
    void post_正常调用_含应付生成提示() {
        PurchaseInvoice invoice = mock(PurchaseInvoice.class);
        when(invoice.getDocNo()).thenReturn("PINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(invoice.getPurchaseReceiptNo()).thenReturn("PR-202606-0001");
        when(invoice.getSupplierId()).thenReturn(1L);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("3600.00"));
        when(purchaseInvoiceAppService.post(eq("PINV-202606-0001"), eq("agent:5")))
                .thenReturn(invoice);

        ToolResult result = postTool.execute(Map.of("doc_no", "PINV-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("note");
        assertThat(result.data().get("note").toString()).contains("应付");
    }

    @Test
    void post_单据不存在_转fail() {
        when(purchaseInvoiceAppService.post(any(), any()))
                .thenThrow(new PurchaseInvoiceNotFoundException("PINV-NOT-EXIST"));
        ToolResult result = postTool.execute(Map.of("doc_no", "PINV-NOT-EXIST"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    // ------------------------------------------------------------------ query

    @Test
    void query_风险级别NORMAL_权限点null() {
        assertThat(queryTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(queryTool.requiredPermission()).isNull();
    }

    @Test
    void query_正常查询_返回头行字段() {
        PurchaseInvoice invoice = mock(PurchaseInvoice.class);
        when(invoice.getDocNo()).thenReturn("PINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(invoice.getPurchaseReceiptNo()).thenReturn("PR-202606-0001");
        when(invoice.getSupplierId()).thenReturn(1L);
        when(invoice.getInvoiceDate()).thenReturn(java.time.LocalDate.now());
        when(invoice.getSupplierInvoiceNo()).thenReturn("INV-12345");
        when(invoice.getRemark()).thenReturn(null);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("3600.00"));
        when(invoice.getLines()).thenReturn(List.of());
        when(purchaseInvoiceAppService.get("PINV-202606-0001")).thenReturn(invoice);

        ToolResult result = queryTool.execute(Map.of("doc_no", "PINV-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "PINV-202606-0001");
        assertThat(result.data()).containsEntry("purchaseReceiptNo", "PR-202606-0001");
        assertThat(result.data()).containsKey("lines");
    }

    // ------------------------------------------------------------------ queryPayables

    @Test
    void queryPayables_风险级别NORMAL_权限点null() {
        assertThat(queryPayablesTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(queryPayablesTool.requiredPermission()).isNull();
    }

    @Test
    void queryPayables_状态不合法_转fail() {
        ToolResult result = queryPayablesTool.execute(Map.of("status", "INVALID_STATUS"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("应付状态不合法");
    }

    @Test
    void queryPayables_不传参数_返回全量() {
        AccountsPayable payable = mock(AccountsPayable.class);
        when(payable.getId()).thenReturn(1L);
        when(payable.getSupplierId()).thenReturn(2L);
        when(payable.getSourceDocNo()).thenReturn("PINV-202606-0001");
        when(payable.getAmount()).thenReturn(new BigDecimal("3600.00"));
        when(payable.getDueDate()).thenReturn(null);
        when(payable.getStatus()).thenReturn(PayableStatus.OPEN);
        when(payable.getSettledAmount()).thenReturn(BigDecimal.ZERO);
        when(payable.outstandingAmount()).thenReturn(new BigDecimal("3600.00"));
        when(purchaseInvoiceAppService.searchPayables(any())).thenReturn(page(List.of(payable)));

        ToolResult result = queryPayablesTool.execute(Map.of(), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("total");
        assertThat(result.data()).containsKey("items");
    }

    @Test
    void queryPayables_按供应商名称解析失败_转fail() {
        when(supplierService.search(any(SupplierQuery.class))).thenReturn(page(List.of()));
        ToolResult result = queryPayablesTool.execute(Map.of("supplier", "不存在供应商"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("供应商");
    }
}
