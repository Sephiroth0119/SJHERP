package com.sjherp.app.tool.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.catalog.CatalogNotFoundException;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitConversion;
import com.sjherp.domain.catalog.UnitService;

/**
 * 商品详情查询工具（M2-T08，NORMAL）：按编码或 id 查单个商品，含多单位换算关系。
 * 查询经 {@link ProductService} 唯一入口。
 */
public class GetProductDetailTool implements Tool {

    public static final String NAME = "get_product_detail";

    private final ProductService productService;
    private final UnitService unitService;

    public GetProductDetailTool(ProductService productService, UnitService unitService) {
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.unitService = Objects.requireNonNull(unitService, "unitService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询单个商品的完整档案（含多单位换算关系）：按商品编码（code）或商品 id 查询，"
                + "两者至少传一个，同时传时以 id 为准。不确定编码时先用 search_products 找到商品。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "id":{"type":"integer","description":"商品 id（search_products 返回的 id）"},\
                "code":{"type":"string","description":"商品编码，精确匹配（如 SKU-202606-0001）"}},\
                "required":[],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Object id = arguments.get("id");
        String code = ArchiveToolSupport.str(arguments.get("code"));
        if (id == null && code == null) {
            return ToolResult.fail("参数 id 与 code 至少传一个");
        }

        Product product;
        if (id != null) {
            try {
                product = productService.get(((Number) id).longValue());
            } catch (CatalogNotFoundException e) {
                return ToolResult.fail("商品不存在: id=" + id);
            }
        } else {
            // 按编码精确查：经领域服务的分页查询（关键字模糊匹配编码）后精确过滤
            Optional<Product> found = productService
                    .search(new ProductQuery(code, null, 1, ProductQuery.MAX_SIZE))
                    .items().stream()
                    .filter(p -> p.getCode().equalsIgnoreCase(code))
                    .findFirst();
            if (found.isEmpty()) {
                return ToolResult.fail("未找到编码为 " + code + " 的商品，可先用 search_products 按名称查找");
            }
            product = found.get();
        }

        Map<Long, Unit> units = unitService.findAll().stream()
                .collect(Collectors.toMap(Unit::getId, u -> u, (a, b) -> a));
        String baseUnitName = unitName(units, product.getBaseUnitId());

        List<Map<String, Object>> conversions = new ArrayList<>();
        for (UnitConversion conversion : product.getUnitConversions()) {
            String unitName = unitName(units, conversion.unitId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("unit", unitName);
            // 精度原则：换算率以字符串承载，不用 JSON 数字
            item.put("rate", conversion.rate().toPlainString());
            item.put("meaning", "1 " + unitName + " = " + conversion.rate().toPlainString() + " " + baseUnitName);
            conversions.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", product.getId());
        data.put("code", product.getCode());
        data.put("name", product.getName());
        data.put("spec", product.getSpec());
        data.put("baseUnit", baseUnitName);
        data.put("barcode", product.getBarcode());
        data.put("status", ArchiveToolSupport.statusLabel(product.getStatus()));
        data.put("remark", product.getRemark());
        data.put("unitConversions", conversions);
        return ToolResult.ok(data);
    }

    private static String unitName(Map<Long, Unit> units, long unitId) {
        Unit unit = units.get(unitId);
        return unit == null ? "未知单位(id=" + unitId + ")" : unit.getName();
    }
}
