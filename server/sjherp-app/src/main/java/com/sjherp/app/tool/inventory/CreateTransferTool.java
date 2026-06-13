package com.sjherp.app.tool.inventory;

import java.math.BigDecimal;
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
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.inventory.InventoryToolSupport.Resolution;
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.app.transfer.TransferDtos.TransferLineRequest;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.inventory.IdempotencyConflictException;
import com.sjherp.domain.inventory.InsufficientStockException;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 库存调拨建单工具（M3-T04，HIGH——产生跨仓两腿库存流水，框架强制确认卡片）：
 * 单次建一张单行调拨单（一个商品从调出仓到调入仓）。建单后单据为草稿，需经审核 + 过账
 * 才真正产生库存流水（过账走 REST，本工具仅建单）。
 *
 * <p>写操作经 {@link TransferAppService} → 领域 TransferService → 库存唯一写入口
 * （CLAUDE.md 原则 1）；单据号 TR- 自动编号；审计操作人记 agent:&lt;userId&gt;。
 * 权限点 inventory:transfer（ADMIN/BOSS/WAREHOUSE）。同仓调拨、数量 ≤ 0、档案停用一律拒绝。
 *
 * <p>放在 inventory 工具包以复用包私有助手 {@link InventoryToolSupport}（仓库/商品名称或编码解析）。
 */
public class CreateTransferTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateTransferTool.class);

    public static final String NAME = "create_transfer";

    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final TransferAppService transferAppService;

    public CreateTransferTool(WarehouseService warehouseService, ProductService productService,
                              TransferAppService transferAppService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.transferAppService = Objects.requireNonNull(transferAppService, "transferAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建库存调拨单（单行）：把一个商品从调出仓（from_warehouse）调拨到调入仓"
                + "（to_warehouse），数量 quantity（基本单位，正数）。调出仓与调入仓不能相同。"
                + "仓库与商品传名称或编码。建单后单据为草稿，需后续审核与过账才真正移动库存"
                + "（调拨按调出仓加权成本扣减、调入仓按同一成本入账，企业库存价值守恒）。"
                + "调用前先在回复正文复述要点（调出仓、调入仓、商品、数量）；系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "from_warehouse":{"type":"string","description":"调出仓库名称或编码"},\
                "to_warehouse":{"type":"string","description":"调入仓库名称或编码（必须与调出仓不同）"},\
                "product":{"type":"string","description":"商品名称或编码"},\
                "quantity":{"type":"string","description":"调拨数量（基本单位，正数，字符串承载，如 \\"100\\"）"},\
                "remark":{"type":"string","description":"调拨说明（可选，如 门店补货）"}},\
                "required":["from_warehouse","to_warehouse","product","quantity"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "inventory:transfer";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Resolution<Warehouse> fromWarehouse = InventoryToolSupport.resolveWarehouse(
                warehouseService, ArchiveToolSupport.str(arguments.get("from_warehouse")));
        if (fromWarehouse.failed()) {
            return ToolResult.fail("调出仓解析失败: " + fromWarehouse.error());
        }
        Resolution<Warehouse> toWarehouse = InventoryToolSupport.resolveWarehouse(
                warehouseService, ArchiveToolSupport.str(arguments.get("to_warehouse")));
        if (toWarehouse.failed()) {
            return ToolResult.fail("调入仓解析失败: " + toWarehouse.error());
        }
        Resolution<Product> product = InventoryToolSupport.resolveProduct(
                productService, ArchiveToolSupport.str(arguments.get("product")));
        if (product.failed()) {
            return ToolResult.fail(product.error());
        }
        BigDecimal quantity = InventoryToolSupport.decimal(arguments.get("quantity"));
        if (quantity == null) {
            return ToolResult.fail("调拨数量 quantity 必填且为正数");
        }

        String operator = ArchiveToolSupport.operator(context);
        try {
            TransferDocument document = transferAppService.create(
                    fromWarehouse.value().getId(), toWarehouse.value().getId(),
                    ArchiveToolSupport.str(arguments.get("remark")),
                    List.of(new TransferLineRequest(product.value().getId(), quantity)), operator);
            log.info("Agent 创建调拨单（docNo={}, from={}, to={}, product={}, qty={}, operator={}, sessionId={}）",
                    document.getDocNo(), fromWarehouse.value().getCode(), toWarehouse.value().getCode(),
                    product.value().getCode(), quantity.toPlainString(), operator, context.sessionId());
            return ToolResult.ok(toData(document, fromWarehouse.value(), toWarehouse.value(),
                    product.value(), quantity));
        } catch (InsufficientStockException | IdempotencyConflictException | IllegalArgumentException e) {
            // 领域校验拒绝（同仓调拨、停用、数量非法等）——宁可拒绝，不可破坏模型
            return ToolResult.fail("调拨单创建被拒绝: " + e.getMessage());
        }
    }

    /** 建单结果 → 工具返回数据（数量一律字符串承载） */
    private static Map<String, Object> toData(TransferDocument document, Warehouse from,
                                              Warehouse to, Product product, BigDecimal quantity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", document.getDocNo());
        data.put("status", document.getStatus().name());
        data.put("fromWarehouse", from.getName());
        data.put("toWarehouse", to.getName());
        data.put("product", product.getName());
        data.put("quantity", quantity.toPlainString());
        data.put("note", "调拨单已创建为草稿，需审核并过账后才真正移动库存");
        return data;
    }
}
