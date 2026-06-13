package com.sjherp.app.tool.sales;

import java.math.BigDecimal;
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
import com.sjherp.app.sales.SalesDtos.SalesDeliveryLineRequest;
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.sales.SalesToolSupport.Resolution;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 创建销售出库单工具（M3-T11，HIGH——框架强制确认卡片）：引用已审核销售订单，从指定仓库按行出库，
 * 建草稿出库单（不动库存）。后续需审核、过账才真正扣减库存并算 COGS。
 *
 * <p>写操作经 {@link SalesDeliveryAppService#create}（CLAUDE.md 原则 1）；仓库与商品按名称或编码解析；
 * 行参数 lines[{so_line_no(整数), product(名称或编码), quantity(字符串)}]；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 sales:delivery（ADMIN/BOSS/WAREHOUSE）。
 */
public class CreateSalesDeliveryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateSalesDeliveryTool.class);

    public static final String NAME = "create_sales_delivery";

    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final SalesDeliveryAppService salesDeliveryAppService;

    public CreateSalesDeliveryTool(WarehouseService warehouseService,
                                   ProductService productService,
                                   SalesDeliveryAppService salesDeliveryAppService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.salesDeliveryAppService = Objects.requireNonNull(salesDeliveryAppService,
                "salesDeliveryAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建销售出库单（草稿）：引用已审核销售订单（sales_order_no），从指定仓库（warehouse）"
                + "按行出库，每行填写 so_line_no（销售订单行号，整数）、product（商品名称或编码）"
                + "和 quantity（发货数量，字符串）。建单后为草稿，不动库存；需审核、过账才真正扣库存并算成本。"
                + "调用前先在回复正文复述要点（销售订单号、出库仓库、各行发货数量）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "sales_order_no":{"type":"string","description":"引用的已审核销售订单号（如 SO-202606-0001）"},\
                "warehouse":{"type":"string","description":"出库仓库名称或编码（如 一号仓 / WH-202606-0001）"},\
                "remark":{"type":"string","description":"出库说明（可选）"},\
                "lines":{"type":"array","description":"出库行（每行对应销售订单一行）","items":{\
                "type":"object","properties":{\
                "so_line_no":{"type":"integer","description":"引用的销售订单行号（整数，如 1）"},\
                "product":{"type":"string","description":"商品名称或编码"},\
                "quantity":{"type":"string","description":"本次发货数量（正数，字符串承载，如 \\"50\\"）"}},\
                "required":["so_line_no","product","quantity"],"additionalProperties":false}}},\
                "required":["sales_order_no","warehouse","lines"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "sales:delivery";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String salesOrderNo = ArchiveToolSupport.str(arguments.get("sales_order_no"));
        if (salesOrderNo == null) {
            return ToolResult.fail("销售订单号 sales_order_no 必填");
        }

        Resolution<Warehouse> warehouse = SalesToolSupport.resolveWarehouse(
                warehouseService, ArchiveToolSupport.str(arguments.get("warehouse")));
        if (warehouse.failed()) {
            return ToolResult.fail("出库仓库解析失败: " + warehouse.error());
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("销售出库单至少要有一行（lines 不能为空）");
        }

        List<SalesDeliveryLineRequest> lines = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("出库行格式不合法：每行须含 so_line_no、product 和 quantity");
            }
            Map<String, Object> row = (Map<String, Object>) lineMap;

            Object soLineNoRaw = row.get("so_line_no");
            if (soLineNoRaw == null) {
                return ToolResult.fail("出库行 so_line_no 必填（整数）");
            }
            Integer soLineNo;
            try {
                soLineNo = ((Number) soLineNoRaw).intValue();
            } catch (ClassCastException e) {
                return ToolResult.fail("出库行 so_line_no 必须为整数: " + soLineNoRaw);
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
                return ToolResult.fail("出库行 quantity 必填且为正数");
            }

            lines.add(new SalesDeliveryLineRequest(soLineNo, product.value().getId(), quantity));
        }

        String operator = ArchiveToolSupport.operator(context);
        String remark = ArchiveToolSupport.str(arguments.get("remark"));
        try {
            SalesDelivery delivery = salesDeliveryAppService.create(
                    salesOrderNo, warehouse.value().getId(), remark, lines, operator);
            log.info("Agent 创建销售出库单（docNo={}, salesOrderNo={}, warehouse={}, lines={}, operator={}, sessionId={}）",
                    delivery.getDocNo(), salesOrderNo, warehouse.value().getCode(),
                    lines.size(), operator, context.sessionId());
            return ToolResult.ok(toData(delivery, warehouse.value()));
        } catch (IllegalArgumentException | IllegalStateTransitionException
                 | IllegalStateException e) {
            return ToolResult.fail("创建销售出库单被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesDelivery delivery, Warehouse warehouse) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", delivery.getDocNo());
        data.put("status", delivery.getStatus().name());
        data.put("salesOrderNo", delivery.getSalesOrderNo());
        data.put("warehouse", warehouse.getName());
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (SalesDeliveryLine line : delivery.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("soLineNo", line.getSoLineNo());
            row.put("productId", line.getProductId());
            row.put("quantity", line.getQuantity().toPlainString());
            lineRows.add(row);
        }
        data.put("lines", lineRows);
        data.put("note", "销售出库单已创建为草稿（未动库存），需审核后过账才真正出库并计算成本");
        return data;
    }
}
