package com.sjherp.app.tool.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceLine;
import com.sjherp.domain.sales.SalesInvoiceNotFoundException;

/**
 * 查询销售发票工具（M3-T11，NORMAL）：按单据号查销售发票头与行项目（出库单号、客户、
 * 发票日期、到期日、各行开票数量/单价/金额、单据状态、总额）。
 *
 * <p>只读经 {@link SalesInvoiceAppService#get}，无权限点（登录即可）。
 */
public class QuerySalesInvoiceTool implements Tool {

    public static final String NAME = "query_sales_invoice";

    private final SalesInvoiceAppService salesInvoiceAppService;

    public QuerySalesInvoiceTool(SalesInvoiceAppService salesInvoiceAppService) {
        this.salesInvoiceAppService = Objects.requireNonNull(salesInvoiceAppService,
                "salesInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询销售发票：按单据号 doc_no（如 SINV-202606-0001）返回发票头（出库单号、客户、"
                + "发票日期、到期日、状态、总金额）与各行（出库行号、商品、开票数量/单价/金额）。"
                + "用户问发票状态、开票内容或应收生成情况时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售发票号（如 SINV-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("销售发票号 doc_no 必填");
        }
        try {
            SalesInvoice invoice = salesInvoiceAppService.get(docNo);
            return ToolResult.ok(toData(invoice));
        } catch (SalesInvoiceNotFoundException e) {
            return ToolResult.fail("销售发票不存在: " + docNo);
        }
    }

    private static Map<String, Object> toData(SalesInvoice invoice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", invoice.getDocNo());
        data.put("status", invoice.getStatus().name());
        data.put("salesDeliveryNo", invoice.getSalesDeliveryNo());
        data.put("customerId", invoice.getCustomerId());
        data.put("invoiceDate", invoice.getInvoiceDate().toString());
        data.put("dueDate", invoice.getDueDate() == null ? null : invoice.getDueDate().toString());
        data.put("remark", invoice.getRemark());
        data.put("totalAmount", invoice.totalAmount().toPlainString());
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (SalesInvoiceLine line : invoice.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("deliveryLineNo", line.getDeliveryLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            row.put("unitPrice", line.getUnitPrice().toPlainString());
            row.put("amount", line.getAmount().toPlainString());
            lineRows.add(row);
        }
        data.put("lines", lineRows);
        return data;
    }
}
