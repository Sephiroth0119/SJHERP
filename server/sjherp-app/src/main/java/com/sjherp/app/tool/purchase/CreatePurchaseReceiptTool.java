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
import com.sjherp.app.purchase.PurchaseDtos.PurchaseReceiptLineRequest;
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.purchase.PurchaseToolSupport.Resolution;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLine;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 创建采购入库单工具（M3-T11，HIGH——建草稿收货单，框架强制确认卡片）：引用已审核采购订单，
 * 按行录入收货数量（及可选收货单价），建出草稿入库单（不动库存）。后续需审核、过账才真正入库。
 *
 * <p>写操作经 {@link PurchaseReceiptAppService#create}（CLAUDE.md 原则 1）；
 * 仓库按名称或编码解析；行 po_line_no 为整数引用，quantity/unit_cost 为字符串承载小数；
 * unit_cost 可省略（省略时领域层取采购订单行单价）；审计操作人记 agent:&lt;userId&gt;。
 * 权限点 purchase:receipt（ADMIN/BOSS/WAREHOUSE）。
 */
public class CreatePurchaseReceiptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreatePurchaseReceiptTool.class);

    public static final String NAME = "create_purchase_receipt";

    private final WarehouseService warehouseService;
    private final PurchaseReceiptAppService purchaseReceiptAppService;

    public CreatePurchaseReceiptTool(WarehouseService warehouseService,
                                     PurchaseReceiptAppService purchaseReceiptAppService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.purchaseReceiptAppService = Objects.requireNonNull(purchaseReceiptAppService,
                "purchaseReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建采购入库单（草稿）：引用已审核采购订单（purchase_order_no），选择收货仓库（warehouse），"
                + "逐行填入收货数量（quantity）及可选收货单价（unit_cost，省略则取采购订单行单价）。"
                + "建单后单据为草稿，不动库存；需后续审核再过账才真正入库并回写采购订单到货量。"
                + "调用前先在回复正文复述要点（采购订单号、仓库、各行收货数量）；"
                + "系统会自动请求用户确认后才创建。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "purchase_order_no":{"type":"string","description":"引用的已审核采购订单号（如 PO-202606-0001）"},\
                "warehouse":{"type":"string","description":"收货仓库名称或编码（如 一号仓 / WH-202606-0001）"},\
                "receipt_date":{"type":"string","description":"收货日期（YYYY-MM-DD，可选，省略取当天）"},\
                "remark":{"type":"string","description":"收货说明（可选）"},\
                "lines":{"type":"array","description":"收货行（每行对应采购订单一行）","items":{\
                "type":"object","properties":{\
                "po_line_no":{"type":"integer","description":"引用的采购订单行号（整数，如 1）"},\
                "quantity":{"type":"string","description":"本次收货数量（正数，字符串承载，如 \\"100\\"）"},\
                "unit_cost":{"type":"string","description":"收货单价（≥0，字符串承载，如 \\"18.00\\"；可选，省略取采购订单行单价）"}},\
                "required":["po_line_no","quantity"],"additionalProperties":false}}},\
                "required":["purchase_order_no","warehouse","lines"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "purchase:receipt";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String purchaseOrderNo = ArchiveToolSupport.str(arguments.get("purchase_order_no"));
        if (purchaseOrderNo == null) {
            return ToolResult.fail("采购订单号 purchase_order_no 必填");
        }

        Resolution<Warehouse> warehouse = PurchaseToolSupport.resolveWarehouse(
                warehouseService, ArchiveToolSupport.str(arguments.get("warehouse")));
        if (warehouse.failed()) {
            return ToolResult.fail("收货仓库解析失败: " + warehouse.error());
        }

        LocalDate receiptDate = null;
        String rawDate = ArchiveToolSupport.str(arguments.get("receipt_date"));
        if (rawDate != null) {
            try {
                receiptDate = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                return ToolResult.fail("收货日期 receipt_date 格式应为 YYYY-MM-DD");
            }
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("采购入库单至少要有一行（lines 不能为空）");
        }

        List<PurchaseReceiptLineRequest> lines = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("收货行格式不合法：每行须含 po_line_no 和 quantity");
            }
            Map<String, Object> row = (Map<String, Object>) lineMap;

            Object poLineNoRaw = row.get("po_line_no");
            if (poLineNoRaw == null) {
                return ToolResult.fail("收货行 po_line_no 必填（整数，如 1）");
            }
            Integer poLineNo;
            try {
                poLineNo = ((Number) poLineNoRaw).intValue();
            } catch (ClassCastException e) {
                return ToolResult.fail("收货行 po_line_no 必须为整数: " + poLineNoRaw);
            }

            BigDecimal quantity;
            try {
                quantity = PurchaseToolSupport.decimal(row.get("quantity"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            if (quantity == null) {
                return ToolResult.fail("收货行 quantity 必填且为正数");
            }

            BigDecimal unitCost;
            try {
                unitCost = PurchaseToolSupport.decimal(row.get("unit_cost"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }

            lines.add(new PurchaseReceiptLineRequest(poLineNo, quantity, unitCost));
        }

        String operator = ArchiveToolSupport.operator(context);
        try {
            PurchaseReceipt receipt = purchaseReceiptAppService.create(
                    purchaseOrderNo, warehouse.value().getId(), receiptDate,
                    ArchiveToolSupport.str(arguments.get("remark")), lines, operator);
            log.info("Agent 创建采购入库单（docNo={}, purchaseOrderNo={}, warehouse={}, lines={}, operator={}, sessionId={}）",
                    receipt.getDocNo(), purchaseOrderNo, warehouse.value().getCode(),
                    lines.size(), operator, context.sessionId());
            return ToolResult.ok(toData(receipt, warehouse.value()));
        } catch (IllegalArgumentException | IllegalStateTransitionException
                 | IllegalStateException e) {
            return ToolResult.fail("创建采购入库单被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseReceipt receipt, Warehouse warehouse) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("purchaseOrderNo", receipt.getPurchaseOrderNo());
        data.put("warehouse", warehouse.getName());
        data.put("receiptDate", receipt.getReceiptDate().toString());
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
        data.put("note", "采购入库单已创建为草稿（未动库存），需审核后再过账才真正入库");
        return data;
    }
}
