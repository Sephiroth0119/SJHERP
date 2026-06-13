package com.sjherp.app.tool.purchase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLine;
import com.sjherp.domain.purchase.PurchaseReceiptNotFoundException;

/**
 * 查询采购入库单工具（M3-T11，NORMAL）：按单据号查采购入库单头与行项目（采购订单号、仓库、
 * 收货日期、各行 po_line_no/商品/收货数量/单价/金额、单据状态）。
 *
 * <p>只读经 {@link PurchaseReceiptAppService#get}，无权限点（登录即可）。
 */
public class QueryPurchaseReceiptTool implements Tool {

    public static final String NAME = "query_purchase_receipt";

    private final PurchaseReceiptAppService purchaseReceiptAppService;

    public QueryPurchaseReceiptTool(PurchaseReceiptAppService purchaseReceiptAppService) {
        this.purchaseReceiptAppService = Objects.requireNonNull(purchaseReceiptAppService,
                "purchaseReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询采购入库单：按单据号 doc_no（如 PR-202606-0001）返回收货单头（采购订单号、仓库、"
                + "收货日期、状态、总金额）与各行（采购订单行号、商品、收货数量/单价/金额）。"
                + "用户问收货单进度、入库数量或内容时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"采购入库单号（如 PR-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("采购入库单号 doc_no 必填");
        }
        try {
            PurchaseReceipt receipt = purchaseReceiptAppService.get(docNo);
            return ToolResult.ok(toData(receipt));
        } catch (PurchaseReceiptNotFoundException e) {
            return ToolResult.fail("采购入库单不存在: " + docNo);
        }
    }

    private static Map<String, Object> toData(PurchaseReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("purchaseOrderNo", receipt.getPurchaseOrderNo());
        data.put("warehouseId", receipt.getWarehouseId());
        data.put("receiptDate", receipt.getReceiptDate().toString());
        data.put("remark", receipt.getRemark());
        data.put("totalAmount", receipt.totalAmount().toPlainString());
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (PurchaseReceiptLine line : receipt.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("poLineNo", line.getPoLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            row.put("unitCost", line.getUnitCost().toPlainString());
            row.put("amount", line.getAmount().toPlainString());
            lineRows.add(row);
        }
        data.put("lines", lineRows);
        return data;
    }
}
