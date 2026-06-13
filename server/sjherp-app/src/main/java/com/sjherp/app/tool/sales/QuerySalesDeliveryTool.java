package com.sjherp.app.tool.sales;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.sales.SalesDeliveryNotFoundException;

/**
 * 查询销售出库单工具（M3-T11，NORMAL）：按单据号查销售出库单头与行项目（销售订单号、仓库、
 * 各行 so_line_no/商品/发货数量/COGS、单据状态、出库总成本）。
 *
 * <p>只读经 {@link SalesDeliveryAppService#get}，无权限点（登录即可）。
 */
public class QuerySalesDeliveryTool implements Tool {

    public static final String NAME = "query_sales_delivery";

    private final SalesDeliveryAppService salesDeliveryAppService;

    public QuerySalesDeliveryTool(SalesDeliveryAppService salesDeliveryAppService) {
        this.salesDeliveryAppService = Objects.requireNonNull(salesDeliveryAppService,
                "salesDeliveryAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询销售出库单：按单据号 doc_no（如 SD-202606-0001）返回出库单头（销售订单号、仓库、"
                + "状态、出库总成本）与各行（订单行号、商品、发货数量、COGS 金额）。"
                + "用户问出库进度、发货情况或成本时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售出库单号（如 SD-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("销售出库单号 doc_no 必填");
        }
        try {
            SalesDelivery delivery = salesDeliveryAppService.get(docNo);
            return ToolResult.ok(toData(delivery));
        } catch (SalesDeliveryNotFoundException e) {
            return ToolResult.fail("销售出库单不存在: " + docNo);
        }
    }

    private static Map<String, Object> toData(SalesDelivery delivery) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", delivery.getDocNo());
        data.put("status", delivery.getStatus().name());
        data.put("salesOrderNo", delivery.getSalesOrderNo());
        data.put("warehouseId", delivery.getWarehouseId());
        data.put("remark", delivery.getRemark());
        data.put("totalCogs", delivery.totalCogs().toPlainString());
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (SalesDeliveryLine line : delivery.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("soLineNo", line.getSoLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            row.put("cogsAmount", line.getCogsAmount() == null ? null : line.getCogsAmount().toPlainString());
            lineRows.add(row);
        }
        data.put("lines", lineRows);
        return data;
    }
}
