package com.sjherp.app.tool.inventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.app.tool.inventory.InventoryToolSupport.Resolution;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 库存余额查询工具（M3-T01c，NORMAL）：按仓库 + 商品（名称或编码）查实时结存
 * 数量/金额/派生加权单价。只读经 {@link TransactionalInventoryService}（balanceOf），
 * 余额真源两列，单价为派生值（拆解 §1.1）。
 */
public class QueryInventoryBalanceTool implements Tool {

    public static final String NAME = "query_inventory_balance";

    private final WarehouseService warehouseService;
    private final ProductService productService;
    private final TransactionalInventoryService inventoryService;

    public QueryInventoryBalanceTool(WarehouseService warehouseService, ProductService productService,
                                     TransactionalInventoryService inventoryService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询某仓库某商品的实时库存余额：返回结存数量（基本单位）、结存金额与加权单价。"
                + "仓库与商品直接传名称或编码（如\"一号仓\"\"SKU-202606-0001\"），系统自动解析；"
                + "解析有歧义时会返回候选清单。用户问\"某仓还有多少某商品\"\"库存够不够\"时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "warehouse":{"type":"string","description":"仓库名称或编码（如 一号仓 / WH-202606-0001）"},\
                "product":{"type":"string","description":"商品名称或编码（如 不锈钢板 304L / SKU-202606-0001）"}},\
                "required":["warehouse","product"],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
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

        InventoryBalanceView view = inventoryService.balanceOf(
                warehouse.value().getId(), product.value().getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("warehouse", warehouse.value().getName());
        data.put("warehouseCode", warehouse.value().getCode());
        data.put("product", product.value().getName());
        data.put("productCode", product.value().getCode());
        // 精度原则：数量/金额/单价一律字符串承载，不用 JSON 数字
        data.put("quantity", view.quantity().toPlainString());
        data.put("costAmount", view.costAmount().toPlainString());
        data.put("unitCost", view.derivedUnitCost() == null ? null : view.derivedUnitCost().toPlainString());
        if (view.quantity().signum() == 0) {
            data.put("note", "当前结存为零（可能从未入库或已出空）");
        }
        return ToolResult.ok(data);
    }
}
