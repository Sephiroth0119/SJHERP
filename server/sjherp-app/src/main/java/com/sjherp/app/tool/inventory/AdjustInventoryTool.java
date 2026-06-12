package com.sjherp.app.tool.inventory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.inventory.InventoryAdjustmentService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.inventory.InventoryToolSupport.Resolution;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.inventory.IdempotencyConflictException;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 库存调整工具（M3-T01c，HIGH——直接产生库存流水，框架强制确认卡片）：
 * 仅支持期初建账（OPENING）与成本调整（COST_ADJUST）两类，日常出入库由
 * 各业务单据驱动（M3-T03+），绝不经本工具。
 *
 * <p>写操作经 {@link InventoryAdjustmentService} → TransactionalInventoryService →
 * InventoryService 唯一写入口（CLAUDE.md 原则 1）；单据号 OP-/CA- 自动编号；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 inventory:adjust（ADMIN/BOSS/WAREHOUSE）。
 */
public class AdjustInventoryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AdjustInventoryTool.class);

    public static final String NAME = "adjust_inventory";

    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final InventoryAdjustmentService adjustmentService;

    public AdjustInventoryTool(WarehouseService warehouseService, ProductService productService,
                               InventoryAdjustmentService adjustmentService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.adjustmentService = Objects.requireNonNull(adjustmentService, "adjustmentService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "库存调整（仅限两类）：OPENING 期初建账（首次为某仓某商品建立库存，需数量 quantity "
                + "与单价 unit_cost）；COST_ADJUST 成本调整（数量不变只调结存金额，需调整额 "
                + "adjust_amount，可为负，典型场景：到票价差、运费入成本）。仓库与商品传名称或编码。"
                + "调用前先在回复正文中复述要点（仓库、商品、数量、单价/调整额）；系统会自动请求用户"
                + "确认后才真正执行。日常出入库走业务单据，不要用本工具。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "type":{"type":"string","enum":["OPENING","COST_ADJUST"],\
                "description":"调整类型：OPENING 期初建账 / COST_ADJUST 成本调整"},\
                "warehouse":{"type":"string","description":"仓库名称或编码"},\
                "product":{"type":"string","description":"商品名称或编码"},\
                "quantity":{"type":"string","description":"期初数量（基本单位，正数，字符串承载，如 \\"100\\"）；仅 OPENING 必填"},\
                "unit_cost":{"type":"string","description":"期初单价（≥0，最多 6 位小数，字符串承载，如 \\"10.00\\"）；仅 OPENING 必填"},\
                "adjust_amount":{"type":"string","description":"成本调整额（可正可负，最多 2 位小数，字符串承载，如 \\"12.62\\" 或 \\"-5.00\\"）；仅 COST_ADJUST 必填"}},\
                "required":["type","warehouse","product"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "inventory:adjust";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String type = ArchiveToolSupport.str(arguments.get("type"));
        if (type == null) {
            return ToolResult.fail("调整类型 type 必填（OPENING / COST_ADJUST）");
        }
        type = type.toUpperCase(Locale.ROOT);
        if (!"OPENING".equals(type) && !"COST_ADJUST".equals(type)) {
            return ToolResult.fail("调整类型仅支持 OPENING / COST_ADJUST: " + type);
        }

        Resolution<Warehouse> warehouse = InventoryToolSupport.resolveWarehouse(
                warehouseService, ArchiveToolSupport.str(arguments.get("warehouse")));
        if (warehouse.failed()) {
            return ToolResult.fail(warehouse.error());
        }
        Resolution<Product> product = InventoryToolSupport.resolveProduct(
                productService, ArchiveToolSupport.str(arguments.get("product")));
        if (product.failed()) {
            return ToolResult.fail(product.error());
        }

        String operator = ArchiveToolSupport.operator(context);
        try {
            StockMovementResult result;
            if ("OPENING".equals(type)) {
                BigDecimal quantity = InventoryToolSupport.decimal(arguments.get("quantity"));
                BigDecimal unitCost = InventoryToolSupport.decimal(arguments.get("unit_cost"));
                if (quantity == null || unitCost == null) {
                    return ToolResult.fail("期初建账（OPENING）必须同时提供 quantity 与 unit_cost");
                }
                result = adjustmentService.opening(warehouse.value().getId(),
                        product.value().getId(), quantity, unitCost, operator);
            } else {
                BigDecimal adjustAmount = InventoryToolSupport.decimal(arguments.get("adjust_amount"));
                if (adjustAmount == null) {
                    return ToolResult.fail("成本调整（COST_ADJUST）必须提供 adjust_amount");
                }
                result = adjustmentService.costAdjust(warehouse.value().getId(),
                        product.value().getId(), adjustAmount, operator);
            }
            log.info("Agent 库存调整（type={}, docNo={}, warehouse={}, product={}, operator={}, sessionId={}）",
                    type, result.srcDocNo(), warehouse.value().getCode(), product.value().getCode(),
                    operator, context.sessionId());
            return ToolResult.ok(toData(result, warehouse.value(), product.value()));
        } catch (InsufficientStockException | IdempotencyConflictException | IllegalArgumentException e) {
            // 领域校验拒绝（停用、数量非法、调整后金额为负等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("库存调整被拒绝: " + e.getMessage());
        }
    }

    /** 过账结果 → 工具返回数据（数量/金额一律字符串承载） */
    private static Map<String, Object> toData(StockMovementResult result,
                                              Warehouse warehouse, Product product) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", result.srcDocNo());
        data.put("txnType", result.txnType().label());
        data.put("warehouse", warehouse.getName());
        data.put("product", product.getName());
        data.put("quantity", result.quantity().toPlainString());
        data.put("unitCost", result.unitCost() == null ? null : result.unitCost().toPlainString());
        data.put("totalCost", result.totalCost().toPlainString());
        data.put("balanceQuantityAfter", result.balanceQuantityAfter().toPlainString());
        data.put("balanceAmountAfter", result.balanceAmountAfter().toPlainString());
        return data;
    }
}
