package com.sjherp.app.tool.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.purchase.PurchaseDtos.PurchaseInvoiceLineRequest;
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceLine;

/**
 * 创建采购发票工具（M3-T11，HIGH——框架强制确认卡片）：引用已过账采购入库单开票，建草稿发票单
 * （三单匹配：开票数量不得超过已收数量，校验在领域层）。供应商从收货链推导，工具不传。
 *
 * <p>写操作经 {@link PurchaseInvoiceAppService#create}（CLAUDE.md 原则 1）；
 * 行参数 lines[{receipt_line_no(整数), quantity(字符串), amount(字符串)}]；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 purchase:invoice（ADMIN/BOSS/ACCOUNTANT）。
 */
public class CreatePurchaseInvoiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreatePurchaseInvoiceTool.class);

    public static final String NAME = "create_purchase_invoice";

    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public CreatePurchaseInvoiceTool(PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.purchaseInvoiceAppService = Objects.requireNonNull(purchaseInvoiceAppService,
                "purchaseInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建采购发票（草稿）：引用已过账采购入库单（purchase_receipt_no），逐行填入开票数量"
                + "（quantity）和开票金额（amount）。供应商由系统从入库链路自动推导，无须传入。"
                + "三单匹配校验：开票数量不得超过已收数量。建单后为草稿，需审核、过账才生成应付账款。"
                + "调用前先在回复正文复述要点（入库单号、各行开票金额）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "purchase_receipt_no":{"type":"string","description":"引用的已过账采购入库单号（如 PR-202606-0001）"},\
                "invoice_date":{"type":"string","description":"发票日期（YYYY-MM-DD，可选，省略取当天）"},\
                "supplier_invoice_no":{"type":"string","description":"供应商发票号（可选，便于对账）"},\
                "remark":{"type":"string","description":"发票说明（可选）"},\
                "lines":{"type":"array","description":"发票行（每行引用一个收货行）","items":{\
                "type":"object","properties":{\
                "receipt_line_no":{"type":"integer","description":"引用的采购入库单行号（整数，如 1）"},\
                "quantity":{"type":"string","description":"开票数量（正数，字符串，如 \\"100\\"）"},\
                "amount":{"type":"string","description":"开票金额（≥0，字符串，如 \\"1800.00\\"）"}},\
                "required":["receipt_line_no","quantity","amount"],"additionalProperties":false}}},\
                "required":["purchase_receipt_no","lines"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "purchase:invoice";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String purchaseReceiptNo = ArchiveToolSupport.str(arguments.get("purchase_receipt_no"));
        if (purchaseReceiptNo == null) {
            return ToolResult.fail("采购入库单号 purchase_receipt_no 必填");
        }

        LocalDate invoiceDate = null;
        String rawDate = ArchiveToolSupport.str(arguments.get("invoice_date"));
        if (rawDate != null) {
            try {
                invoiceDate = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                return ToolResult.fail("发票日期 invoice_date 格式应为 YYYY-MM-DD");
            }
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("采购发票至少要有一行（lines 不能为空）");
        }

        List<PurchaseInvoiceLineRequest> lines = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("发票行格式不合法：每行须含 receipt_line_no、quantity 和 amount");
            }
            Map<String, Object> row = (Map<String, Object>) lineMap;

            Object receiptLineNoRaw = row.get("receipt_line_no");
            if (receiptLineNoRaw == null) {
                return ToolResult.fail("发票行 receipt_line_no 必填（整数）");
            }
            Integer receiptLineNo;
            try {
                receiptLineNo = ((Number) receiptLineNoRaw).intValue();
            } catch (ClassCastException e) {
                return ToolResult.fail("发票行 receipt_line_no 必须为整数: " + receiptLineNoRaw);
            }

            BigDecimal quantity;
            try {
                quantity = PurchaseToolSupport.decimal(row.get("quantity"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            if (quantity == null) {
                return ToolResult.fail("发票行 quantity 必填");
            }

            BigDecimal amount;
            try {
                amount = PurchaseToolSupport.decimal(row.get("amount"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            if (amount == null) {
                return ToolResult.fail("发票行 amount 必填");
            }

            lines.add(new PurchaseInvoiceLineRequest(receiptLineNo, quantity, amount));
        }

        String operator = ArchiveToolSupport.operator(context);
        String supplierInvoiceNo = ArchiveToolSupport.str(arguments.get("supplier_invoice_no"));
        String remark = ArchiveToolSupport.str(arguments.get("remark"));
        try {
            PurchaseInvoice invoice = purchaseInvoiceAppService.create(
                    purchaseReceiptNo, invoiceDate, supplierInvoiceNo, remark, lines, operator);
            log.info("Agent 创建采购发票（docNo={}, purchaseReceiptNo={}, lines={}, operator={}, sessionId={}）",
                    invoice.getDocNo(), purchaseReceiptNo, lines.size(), operator, context.sessionId());
            return ToolResult.ok(toData(invoice));
        } catch (IllegalArgumentException | IllegalStateTransitionException
                 | IllegalStateException e) {
            return ToolResult.fail("创建采购发票被拒绝: " + e.getMessage());
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
        data.put("note", "采购发票已创建为草稿，需审核后过账才生成应付账款");
        return data;
    }
}
