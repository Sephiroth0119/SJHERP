package com.sjherp.app.tool.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderLine;
import com.sjherp.domain.sales.SalesOrderNotFoundException;

/**
 * 销售订单查询工具（M3-T08，NORMAL）：按单据号查订单头与行项目（客户、订单日期、各行商品/数量/
 * 单价/金额/累计发货量/剩余可发量、单据状态、总金额）。只读经 {@link SalesOrderAppService#get}。
 *
 * <p>用户问「SO-202606-0001 这张销售订单到哪一步了」「这单卖了什么、发了多少」时调用。
 */
public class QuerySalesOrderTool implements Tool {

    public static final String NAME = "query_sales_order";

    private final SalesOrderAppService salesOrderAppService;

    public QuerySalesOrderTool(SalesOrderAppService salesOrderAppService) {
        this.salesOrderAppService = Objects.requireNonNull(salesOrderAppService, "salesOrderAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询销售订单：按单据号 doc_no（如 SO-202606-0001）返回客户、订单日期、各行商品与"
                + "数量/单价/金额/累计发货量/剩余可发量、单据状态、订单总金额。用户问订单进度或内容时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售订单号（如 SO-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("销售订单号 doc_no 必填");
        }
        try {
            SalesOrder order = salesOrderAppService.get(docNo);
            return ToolResult.ok(toData(order));
        } catch (SalesOrderNotFoundException e) {
            return ToolResult.fail(e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", order.getDocNo());
        data.put("status", order.getStatus().name());
        data.put("customerId", order.getCustomerId());
        data.put("orderDate", order.getOrderDate().toString());
        data.put("remark", order.getRemark());
        data.put("totalAmount", order.totalAmount().toPlainString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SalesOrderLine line : order.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            row.put("unitPrice", line.getUnitPrice().toPlainString());
            row.put("amount", line.getAmount().toPlainString());
            row.put("deliveredQty", line.getDeliveredQty().toPlainString());
            row.put("remainingQty", line.remainingQty().toPlainString());
            lines.add(row);
        }
        data.put("lines", lines);
        return data;
    }
}
