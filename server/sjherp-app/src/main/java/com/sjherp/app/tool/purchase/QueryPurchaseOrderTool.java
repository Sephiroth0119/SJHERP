package com.sjherp.app.tool.purchase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderLine;
import com.sjherp.domain.purchase.PurchaseOrderNotFoundException;

/**
 * 采购订单查询工具（M3-T05，NORMAL）：按单据号查采购订单头与行项目（供应商、下单日期、各行商品、
 * 订购数量/单价/金额、已到货量/未到货量、单据状态）。只读经 {@link PurchaseOrderAppService#get}。
 *
 * <p>用户问「PO-202606-0001 采购单到哪一步了」「这张采购单订了什么、到货了多少」时调用。
 */
public class QueryPurchaseOrderTool implements Tool {

    public static final String NAME = "query_purchase_order";

    private final PurchaseOrderAppService purchaseOrderAppService;

    public QueryPurchaseOrderTool(PurchaseOrderAppService purchaseOrderAppService) {
        this.purchaseOrderAppService = Objects.requireNonNull(purchaseOrderAppService,
                "purchaseOrderAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询采购订单：按单据号 doc_no（如 PO-202606-0001）返回供应商、下单日期、各行商品与"
                + "订购数量/单价/金额、已到货量/未到货量、单据状态。用户问采购单进度、到货情况或内容时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"采购订单号（如 PO-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("采购订单号 doc_no 必填");
        }
        try {
            PurchaseOrder order = purchaseOrderAppService.get(docNo);
            return ToolResult.ok(toData(order));
        } catch (PurchaseOrderNotFoundException e) {
            return ToolResult.fail(e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", order.getDocNo());
        data.put("status", order.getStatus().name());
        data.put("supplierId", order.getSupplierId());
        data.put("orderDate", order.getOrderDate().toString());
        data.put("remark", order.getRemark());
        data.put("totalAmount", order.totalAmount().toPlainString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PurchaseOrderLine line : order.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            row.put("unitPrice", line.getUnitPrice().toPlainString());
            row.put("amount", line.getAmount().toPlainString());
            row.put("receivedQty", line.getReceivedQty().toPlainString());
            row.put("outstandingQty", line.outstandingQty().toPlainString());
            lines.add(row);
        }
        data.put("lines", lines);
        return data;
    }
}
