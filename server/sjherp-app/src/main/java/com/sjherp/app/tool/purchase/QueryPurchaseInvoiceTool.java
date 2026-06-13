package com.sjherp.app.tool.purchase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceLine;
import com.sjherp.domain.purchase.PurchaseInvoiceNotFoundException;

/**
 * 查询采购发票工具（M3-T11，NORMAL）：按单据号查采购发票头与行项目（收货单号、供应商、
 * 发票日期、供应商发票号、各行开票数量/金额、单据状态）。
 *
 * <p>只读经 {@link PurchaseInvoiceAppService#get}，无权限点（登录即可）。
 */
public class QueryPurchaseInvoiceTool implements Tool {

    public static final String NAME = "query_purchase_invoice";

    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public QueryPurchaseInvoiceTool(PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.purchaseInvoiceAppService = Objects.requireNonNull(purchaseInvoiceAppService,
                "purchaseInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询采购发票：按单据号 doc_no（如 PINV-202606-0001）返回发票头（收货单号、供应商、"
                + "发票日期、供应商发票号、状态、总金额）与各行（收货行号、商品、开票数量/金额）。"
                + "用户问发票状态、开票内容或应付生成情况时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"采购发票号（如 PINV-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("采购发票号 doc_no 必填");
        }
        try {
            PurchaseInvoice invoice = purchaseInvoiceAppService.get(docNo);
            return ToolResult.ok(toData(invoice));
        } catch (PurchaseInvoiceNotFoundException e) {
            return ToolResult.fail("采购发票不存在: " + docNo);
        }
    }

    private static Map<String, Object> toData(PurchaseInvoice invoice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", invoice.getDocNo());
        data.put("status", invoice.getStatus().name());
        data.put("purchaseReceiptNo", invoice.getPurchaseReceiptNo());
        data.put("supplierId", invoice.getSupplierId());
        data.put("invoiceDate", invoice.getInvoiceDate().toString());
        data.put("supplierInvoiceNo", invoice.getSupplierInvoiceNo());
        data.put("remark", invoice.getRemark());
        data.put("totalAmount", invoice.totalAmount().toPlainString());
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (PurchaseInvoiceLine line : invoice.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("receiptLineNo", line.getReceiptLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            row.put("amount", line.getAmount().toPlainString());
            lineRows.add(row);
        }
        data.put("lines", lineRows);
        return data;
    }
}
