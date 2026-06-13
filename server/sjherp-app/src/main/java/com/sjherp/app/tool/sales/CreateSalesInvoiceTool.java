package com.sjherp.app.tool.sales;

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
import com.sjherp.app.sales.SalesDtos.SalesInvoiceLineRequest;
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.sales.SalesToolSupport.Resolution;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceLine;

/**
 * 创建销售发票工具（M3-T11，HIGH——框架强制确认卡片）：引用已过账销售出库单开票，建草稿发票。
 * 客户从出库单→销售订单链路自动推导，工具不传。三单匹配校验（开票数量不超过已发数量）在领域层。
 *
 * <p>写操作经 {@link SalesInvoiceAppService#create}（CLAUDE.md 原则 1）；
 * 行参数 lines[{delivery_line_no(整数), product(名称/编码), quantity(字符串), unit_price(字符串)}]；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 sales:invoice（ADMIN/BOSS/ACCOUNTANT）。
 */
public class CreateSalesInvoiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateSalesInvoiceTool.class);

    public static final String NAME = "create_sales_invoice";

    private final ProductService productService;
    private final SalesInvoiceAppService salesInvoiceAppService;

    public CreateSalesInvoiceTool(ProductService productService,
                                  SalesInvoiceAppService salesInvoiceAppService) {
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.salesInvoiceAppService = Objects.requireNonNull(salesInvoiceAppService,
                "salesInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建销售发票（草稿）：引用已过账销售出库单（sales_delivery_no），逐行填入出库行号"
                + "（delivery_line_no）、商品（名称或编码）、开票数量（quantity）和单价（unit_price）。"
                + "客户由系统从出库链路自动推导，无须传入。开票数量不得超过已发数量。"
                + "建单后为草稿，需审核、过账才生成应收账款。"
                + "调用前先在回复正文复述要点（出库单号、各行开票数量与单价）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "sales_delivery_no":{"type":"string","description":"引用的已过账销售出库单号（如 SD-202606-0001）"},\
                "invoice_date":{"type":"string","description":"开票日期（YYYY-MM-DD，可选，省略取当天）"},\
                "due_date":{"type":"string","description":"到期日（YYYY-MM-DD，可选）"},\
                "remark":{"type":"string","description":"发票说明（可选）"},\
                "lines":{"type":"array","description":"发票行（每行引用一个出库行）","items":{\
                "type":"object","properties":{\
                "delivery_line_no":{"type":"integer","description":"引用的出库行号（整数，如 1）"},\
                "product":{"type":"string","description":"商品名称或编码"},\
                "quantity":{"type":"string","description":"开票数量（正数，字符串，如 \\"50\\"）"},\
                "unit_price":{"type":"string","description":"开票单价（≥0，字符串，如 \\"60.00\\"）"}},\
                "required":["delivery_line_no","product","quantity","unit_price"],"additionalProperties":false}}},\
                "required":["sales_delivery_no","lines"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "sales:invoice";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String salesDeliveryNo = ArchiveToolSupport.str(arguments.get("sales_delivery_no"));
        if (salesDeliveryNo == null) {
            return ToolResult.fail("销售出库单号 sales_delivery_no 必填");
        }

        LocalDate invoiceDate = null;
        String rawInvoiceDate = ArchiveToolSupport.str(arguments.get("invoice_date"));
        if (rawInvoiceDate != null) {
            try {
                invoiceDate = LocalDate.parse(rawInvoiceDate);
            } catch (DateTimeParseException e) {
                return ToolResult.fail("开票日期 invoice_date 格式应为 YYYY-MM-DD");
            }
        }

        LocalDate dueDate = null;
        String rawDueDate = ArchiveToolSupport.str(arguments.get("due_date"));
        if (rawDueDate != null) {
            try {
                dueDate = LocalDate.parse(rawDueDate);
            } catch (DateTimeParseException e) {
                return ToolResult.fail("到期日 due_date 格式应为 YYYY-MM-DD");
            }
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("销售发票至少要有一行（lines 不能为空）");
        }

        List<SalesInvoiceLineRequest> lines = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("发票行格式不合法：每行须含 delivery_line_no、product、quantity 和 unit_price");
            }
            Map<String, Object> row = (Map<String, Object>) lineMap;

            Object deliveryLineNoRaw = row.get("delivery_line_no");
            if (deliveryLineNoRaw == null) {
                return ToolResult.fail("发票行 delivery_line_no 必填（整数）");
            }
            Integer deliveryLineNo;
            try {
                deliveryLineNo = ((Number) deliveryLineNoRaw).intValue();
            } catch (ClassCastException e) {
                return ToolResult.fail("发票行 delivery_line_no 必须为整数: " + deliveryLineNoRaw);
            }

            Resolution<Product> product = SalesToolSupport.resolveProduct(
                    productService, ArchiveToolSupport.str(row.get("product")));
            if (product.failed()) {
                return ToolResult.fail(product.error());
            }

            BigDecimal quantity;
            try {
                quantity = SalesToolSupport.decimal(row.get("quantity"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            if (quantity == null) {
                return ToolResult.fail("发票行 quantity 必填");
            }

            BigDecimal unitPrice;
            try {
                unitPrice = SalesToolSupport.decimal(row.get("unit_price"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            if (unitPrice == null) {
                return ToolResult.fail("发票行 unit_price 必填");
            }

            lines.add(new SalesInvoiceLineRequest(deliveryLineNo, product.value().getId(), quantity, unitPrice));
        }

        String operator = ArchiveToolSupport.operator(context);
        String remark = ArchiveToolSupport.str(arguments.get("remark"));
        try {
            SalesInvoice invoice = salesInvoiceAppService.create(
                    salesDeliveryNo, invoiceDate, dueDate, remark, lines, operator);
            log.info("Agent 创建销售发票（docNo={}, salesDeliveryNo={}, lines={}, operator={}, sessionId={}）",
                    invoice.getDocNo(), salesDeliveryNo, lines.size(), operator, context.sessionId());
            return ToolResult.ok(toData(invoice));
        } catch (IllegalArgumentException | IllegalStateTransitionException
                 | IllegalStateException e) {
            return ToolResult.fail("创建销售发票被拒绝: " + e.getMessage());
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
        data.put("note", "销售发票已创建为草稿，需审核后过账才生成应收账款");
        return data;
    }
}
