package com.sjherp.app.tool.inventory;

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
import com.sjherp.app.stocktake.StocktakeDtos.CountLineInput;
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.inventory.InventoryToolSupport.Resolution;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.inventory.IdempotencyConflictException;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountLine;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 创建盘点单工具（M3-T03，HIGH——产生盘点单据并将进入过账链路，框架强制确认卡片）。
 *
 * <p>仅创建草稿盘点单：单仓 + 行项目（每行一个商品 + 可选零库存盘盈录入单价）。
 * 建单账面快照由后端用库存余额自动填，无须传入。后续录入实盘/审核/过账经 REST 或
 * 专门工具完成（本工具不做实盘录入，避免一次确认卡片承载过多写入）。
 *
 * <p>写操作经 {@link StocktakeService}（外层事务）→ 领域 StockCountService → 库存唯一写入口
 * （CLAUDE.md 原则 1）；单据号 SC- 自动编号；审计操作人记 agent:&lt;userId&gt;。
 * 权限点 inventory:count（ADMIN/BOSS/WAREHOUSE）。
 */
public class CreateStockCountTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateStockCountTool.class);

    public static final String NAME = "create_stock_count";

    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final StocktakeService stocktakeService;

    public CreateStockCountTool(WarehouseService warehouseService, ProductService productService,
                                StocktakeService stocktakeService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.stocktakeService = Objects.requireNonNull(stocktakeService, "stocktakeService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建库存盘点单（草稿）：对某个仓库的若干商品发起盘点。每行一个商品；"
                + "建单时系统自动记录各商品的账面数量作为对照基准。仓库与商品传名称或编码。"
                + "若某商品当前库存为零却预期会盘盈（盘出额外数量），需为该行提供盘盈单价 "
                + "entered_unit_cost（零库存盘盈无法从账面派生单价）。建单后还需逐行录入实盘数量、"
                + "审核、过账才会真正产生盘盈/盘亏的库存变动（这些步骤目前在系统界面或后续工具完成）。"
                + "调用前先在回复正文复述要点（仓库、参与盘点的商品清单）；系统会自动请求用户确认后才创建。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "warehouse":{"type":"string","description":"盘点仓库名称或编码（如 一号仓 / WH-202606-0001）"},\
                "remark":{"type":"string","description":"盘点说明，可选（如 2026 年 6 月月末盘点）"},\
                "lines":{"type":"array","description":"盘点行（每行一个商品）","items":{\
                "type":"object","properties":{\
                "product":{"type":"string","description":"商品名称或编码"},\
                "entered_unit_cost":{"type":"string","description":"零库存盘盈单价（≥0，最多 6 位小数，字符串承载，如 \\"10.00\\"）；仅当该商品当前零库存且预期盘盈时必填，否则省略"}},\
                "required":["product"],"additionalProperties":false}}},\
                "required":["warehouse","lines"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "inventory:count";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Resolution<Warehouse> warehouse = InventoryToolSupport.resolveWarehouse(
                warehouseService, ArchiveToolSupport.str(arguments.get("warehouse")));
        if (warehouse.failed()) {
            return ToolResult.fail(warehouse.error());
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("盘点单至少要有一行（lines 不能为空）");
        }

        List<CountLineInput> lines = new ArrayList<>(lineList.size());
        List<ResolvedProductRef> resolved = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("盘点行格式不合法：每行须含 product");
            }
            Resolution<Product> product = InventoryToolSupport.resolveProduct(
                    productService, ArchiveToolSupport.str(((Map<String, Object>) lineMap).get("product")));
            if (product.failed()) {
                return ToolResult.fail(product.error());
            }
            BigDecimal enteredUnitCost;
            try {
                enteredUnitCost = InventoryToolSupport.decimal(
                        ((Map<String, Object>) lineMap).get("entered_unit_cost"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            lines.add(new CountLineInput(product.value().getId(), enteredUnitCost));
            resolved.add(new ResolvedProductRef(product.value().getName(), product.value().getCode()));
        }

        String operator = ArchiveToolSupport.operator(context);
        try {
            StockCountDocument document = stocktakeService.create(
                    warehouse.value().getId(), ArchiveToolSupport.str(arguments.get("remark")),
                    lines, operator);
            log.info("Agent 创建盘点单（docNo={}, warehouse={}, lines={}, operator={}, sessionId={}）",
                    document.getDocNo(), warehouse.value().getCode(), lines.size(), operator,
                    context.sessionId());
            return ToolResult.ok(toData(document, warehouse.value(), resolved));
        } catch (InsufficientStockException | IdempotencyConflictException
                 | IllegalStateTransitionException | IllegalStateException | IllegalArgumentException e) {
            // 领域校验拒绝（停用、商品重复、零库存盘盈缺成本等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("创建盘点单被拒绝: " + e.getMessage());
        }
    }

    /** 建单结果 → 工具返回数据（账面快照数量原样字符串承载） */
    private static Map<String, Object> toData(StockCountDocument document, Warehouse warehouse,
                                              List<ResolvedProductRef> resolved) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", document.getDocNo());
        data.put("status", document.getStatus().name());
        data.put("warehouse", warehouse.getName());
        data.put("remark", document.getRemark());
        List<Map<String, Object>> lines = new ArrayList<>();
        List<StockCountLine> domainLines = document.getLines();
        for (int i = 0; i < domainLines.size(); i++) {
            StockCountLine line = domainLines.get(i);
            ResolvedProductRef ref = resolved.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.getLineNo());
            row.put("product", ref.name());
            row.put("productCode", ref.code());
            row.put("snapshotQty", line.getSnapshotQty().toPlainString());
            row.put("enteredUnitCost",
                    line.getEnteredUnitCost() == null ? null : line.getEnteredUnitCost().toPlainString());
            lines.add(row);
        }
        data.put("lines", lines);
        data.put("note", "盘点单已创建为草稿，请逐行录入实盘数量后审核、过账");
        return data;
    }

    /** 行解析出的商品展示信息（与领域行同序对齐） */
    private record ResolvedProductRef(String name, String code) {
    }
}
