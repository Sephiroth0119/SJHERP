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
import com.sjherp.app.receivable.ReceivableAppService;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.partner.Customer;
import com.sjherp.domain.partner.CustomerQuery;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableStatus;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceNotFoundException;

/**
 * 销售发票工具组单测（M3-T11）：create/approve/post/query_sales_invoice + query_receivables
 * 的风险级别/权限点、多行解析、商品名称解析、operator 前缀、AppService verify、错误路径转 fail。
 */
class SalesInvoiceToolsTest {

    private ProductService productService;
    private SalesInvoiceAppService salesInvoiceAppService;
    private CustomerService customerService;
    private ReceivableAppService receivableAppService;
    private CreateSalesInvoiceTool createTool;
    private ApproveSalesInvoiceTool approveTool;
    private PostSalesInvoiceTool postTool;
    private QuerySalesInvoiceTool queryTool;
    private QueryReceivablesTool queryReceivablesTool;
    private final ToolContext context = new ToolContext("session-5", "13", "操作销售发票");

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        salesInvoiceAppService = mock(SalesInvoiceAppService.class);
        customerService = mock(CustomerService.class);
        receivableAppService = mock(ReceivableAppService.class);
        createTool = new CreateSalesInvoiceTool(productService, salesInvoiceAppService);
        approveTool = new ApproveSalesInvoiceTool(salesInvoiceAppService);
        postTool = new PostSalesInvoiceTool(salesInvoiceAppService);
        queryTool = new QuerySalesInvoiceTool(salesInvoiceAppService);
        queryReceivablesTool = new QueryReceivablesTool(customerService, receivableAppService);
    }

    private static Product product() {
        return Product.restore(2L, "SKU-202606-0001", "可乐", null, null, 1L, null,
                ArchiveStatus.ENABLED, null, List.of(), "t", Instant.now(), "t", Instant.now());
    }

    private static <T> PageResult<T> page(List<T> items) {
        return new PageResult<>(items, items.size(), 1, 10);
    }

    // ------------------------------------------------------------------ create

    @Test
    void create_风险级别HIGH_权限点sales_invoice() {
        assertThat(createTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(createTool.requiredPermission()).isEqualTo("sales:invoice");
    }

    @Test
    void create_sales_delivery_no缺失_失败() {
        ToolResult result = createTool.execute(Map.of(
                "lines", List.of(Map.of("delivery_line_no", 1, "product", "可乐", "quantity", "50", "unit_price", "6.00"))),
                context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("sales_delivery_no");
        verifyNoInteractions(salesInvoiceAppService);
    }

    @Test
    void create_lines为空_失败() {
        ToolResult result = createTool.execute(Map.of(
                "sales_delivery_no", "SD-202606-0001", "lines", List.of()), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("lines");
        verifyNoInteractions(salesInvoiceAppService);
    }

    @Test
    void create_商品解析失败_不触碰AppService() {
        when(productService.search(any(ProductQuery.class))).thenReturn(page(List.of()));
        ToolResult result = createTool.execute(Map.of(
                "sales_delivery_no", "SD-202606-0001",
                "lines", List.of(Map.of("delivery_line_no", 1, "product", "不存在商品",
                        "quantity", "50", "unit_price", "6.00"))),
                context);
        assertThat(result.success()).isFalse();
        verifyNoInteractions(salesInvoiceAppService);
    }

    @Test
    void create_正常多行解析_verify() {
        when(productService.search(any(ProductQuery.class))).thenReturn(page(List.of(product())));

        SalesInvoice invoice = mock(SalesInvoice.class);
        when(invoice.getDocNo()).thenReturn("SINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.DRAFT);
        when(invoice.getSalesDeliveryNo()).thenReturn("SD-202606-0001");
        when(invoice.getCustomerId()).thenReturn(5L);
        when(invoice.getInvoiceDate()).thenReturn(java.time.LocalDate.now());
        when(invoice.getDueDate()).thenReturn(null);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("300.00"));
        when(invoice.getLines()).thenReturn(List.of());
        when(salesInvoiceAppService.create(
                eq("SD-202606-0001"), isNull(), isNull(), isNull(), any(), eq("agent:13")))
                .thenReturn(invoice);

        ToolResult result = createTool.execute(Map.of(
                "sales_delivery_no", "SD-202606-0001",
                "lines", List.of(
                        Map.of("delivery_line_no", 1, "product", "可乐", "quantity", "50", "unit_price", "6.00")
                )), context);

        assertThat(result.success()).isTrue();
        verify(salesInvoiceAppService).create(
                eq("SD-202606-0001"), isNull(), isNull(), isNull(), any(), eq("agent:13"));
    }

    // ------------------------------------------------------------------ approve

    @Test
    void approve_风险级别HIGH_权限点sales_invoice() {
        assertThat(approveTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(approveTool.requiredPermission()).isEqualTo("sales:invoice");
    }

    @Test
    void approve_doc_no缺失_失败() {
        ToolResult result = approveTool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        verifyNoInteractions(salesInvoiceAppService);
    }

    @Test
    void approve_正常调用_verify() {
        SalesInvoice invoice = mock(SalesInvoice.class);
        when(invoice.getDocNo()).thenReturn("SINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.APPROVED);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("1800.00"));
        when(salesInvoiceAppService.approve(eq("SINV-202606-0001"), eq("agent:13")))
                .thenReturn(invoice);

        ToolResult result = approveTool.execute(Map.of("doc_no", "SINV-202606-0001"), context);
        assertThat(result.success()).isTrue();
        verify(salesInvoiceAppService).approve(eq("SINV-202606-0001"), eq("agent:13"));
    }

    @Test
    void approve_领域拒绝_转fail() {
        when(salesInvoiceAppService.approve(any(), any()))
                .thenThrow(new IllegalStateTransitionException("SINV-202606-0001",
                        DocumentStatus.COMPLETED, DocumentStatus.APPROVED));
        ToolResult result = approveTool.execute(Map.of("doc_no", "SINV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("被拒绝");
    }

    // ------------------------------------------------------------------ post

    @Test
    void post_风险级别HIGH_权限点sales_invoice() {
        assertThat(postTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(postTool.requiredPermission()).isEqualTo("sales:invoice");
    }

    @Test
    void post_正常调用_含应收生成提示() {
        SalesInvoice invoice = mock(SalesInvoice.class);
        when(invoice.getDocNo()).thenReturn("SINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(invoice.getSalesDeliveryNo()).thenReturn("SD-202606-0001");
        when(invoice.getCustomerId()).thenReturn(5L);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("300.00"));
        when(salesInvoiceAppService.post(eq("SINV-202606-0001"), eq("agent:13")))
                .thenReturn(invoice);

        ToolResult result = postTool.execute(Map.of("doc_no", "SINV-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("note");
        assertThat(result.data().get("note").toString()).contains("应收");
    }

    @Test
    void post_单据不存在_转fail() {
        when(salesInvoiceAppService.post(any(), any()))
                .thenThrow(new SalesInvoiceNotFoundException("SINV-NOT-EXIST"));
        ToolResult result = postTool.execute(Map.of("doc_no", "SINV-NOT-EXIST"), context);
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
        SalesInvoice invoice = mock(SalesInvoice.class);
        when(invoice.getDocNo()).thenReturn("SINV-202606-0001");
        when(invoice.getStatus()).thenReturn(DocumentStatus.COMPLETED);
        when(invoice.getSalesDeliveryNo()).thenReturn("SD-202606-0001");
        when(invoice.getCustomerId()).thenReturn(5L);
        when(invoice.getInvoiceDate()).thenReturn(java.time.LocalDate.now());
        when(invoice.getDueDate()).thenReturn(null);
        when(invoice.getRemark()).thenReturn(null);
        when(invoice.totalAmount()).thenReturn(new BigDecimal("300.00"));
        when(invoice.getLines()).thenReturn(List.of());
        when(salesInvoiceAppService.get("SINV-202606-0001")).thenReturn(invoice);

        ToolResult result = queryTool.execute(Map.of("doc_no", "SINV-202606-0001"), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "SINV-202606-0001");
        assertThat(result.data()).containsEntry("salesDeliveryNo", "SD-202606-0001");
        assertThat(result.data()).containsKey("lines");
    }

    // ------------------------------------------------------------------ queryReceivables

    @Test
    void queryReceivables_风险级别NORMAL_权限点null() {
        assertThat(queryReceivablesTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(queryReceivablesTool.requiredPermission()).isNull();
    }

    @Test
    void queryReceivables_状态不合法_转fail() {
        ToolResult result = queryReceivablesTool.execute(Map.of("status", "WRONG"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("应收状态不合法");
    }

    @Test
    void queryReceivables_不传参数_返回全量() {
        AccountsReceivable receivable = mock(AccountsReceivable.class);
        when(receivable.getId()).thenReturn(1L);
        when(receivable.getCustomerId()).thenReturn(5L);
        when(receivable.getSourceDocNo()).thenReturn("SINV-202606-0001");
        when(receivable.getAmount()).thenReturn(new BigDecimal("300.00"));
        when(receivable.getDueDate()).thenReturn(null);
        when(receivable.getStatus()).thenReturn(ReceivableStatus.OPEN);
        when(receivable.getSettledAmount()).thenReturn(BigDecimal.ZERO);
        when(receivable.openAmount()).thenReturn(new BigDecimal("300.00"));
        when(receivableAppService.search(any())).thenReturn(page(List.of(receivable)));

        ToolResult result = queryReceivablesTool.execute(Map.of(), context);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("total");
        assertThat(result.data()).containsKey("items");
    }

    @Test
    void queryReceivables_按客户名称解析失败_转fail() {
        when(customerService.search(any(CustomerQuery.class))).thenReturn(page(List.of()));
        ToolResult result = queryReceivablesTool.execute(Map.of("customer", "不存在客户"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("客户");
    }
}
