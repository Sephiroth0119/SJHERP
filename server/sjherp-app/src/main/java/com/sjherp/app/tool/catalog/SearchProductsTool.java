package com.sjherp.app.tool.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.PageResult;

/**
 * 商品档案查询工具（M2-T08，NORMAL）：关键字 + 状态过滤，返回精简列表（最多 10 条）。
 * 查询经 {@link ProductService} 唯一入口；单位名称经 {@link UnitService} 解析。
 */
public class SearchProductsTool implements Tool {

    public static final String NAME = "search_products";

    private final ProductService productService;
    private final UnitService unitService;

    public SearchProductsTool(ProductService productService, UnitService unitService) {
        this.productService = Objects.requireNonNull(productService, "productService 不能为空");
        this.unitService = Objects.requireNonNull(unitService, "unitService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询商品档案：按关键字（模糊匹配编码/名称/条码）和状态过滤，"
                + "返回精简列表（编码/名称/规格/基本单位/状态，最多 10 条）。"
                + "用户问\"有哪些商品\"\"查一下某商品\"时调用；需要换算关系等完整信息用 get_product_detail。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "keyword":{"type":"string","description":"关键字，模糊匹配商品编码/名称/条码；不传表示不按关键字过滤"},\
                "status":{"type":"string","enum":["ENABLED","DISABLED"],\
                "description":"档案状态过滤：ENABLED 启用 / DISABLED 停用；不传表示全部"}},\
                "required":[],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        PageResult<Product> result = productService.search(new ProductQuery(
                ArchiveToolSupport.str(arguments.get("keyword")),
                ArchiveToolSupport.parseStatus(arguments.get("status")),
                1, ArchiveToolSupport.MAX_ITEMS));

        // 单位名称解析：一次拉全（单位档案量级很小），避免逐条查询
        Map<Long, String> unitNames = unitService.findAll().stream()
                .collect(Collectors.toMap(Unit::getId, Unit::getName, (a, b) -> a));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Product product : result.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", product.getId());
            item.put("code", product.getCode());
            item.put("name", product.getName());
            item.put("spec", product.getSpec());
            item.put("baseUnit", unitNames.getOrDefault(product.getBaseUnitId(),
                    "未知单位(id=" + product.getBaseUnitId() + ")"));
            item.put("status", ArchiveToolSupport.statusLabel(product.getStatus()));
            items.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.total());
        data.put("items", items);
        if (result.total() > items.size()) {
            data.put("note", "共 " + result.total() + " 条，仅返回前 " + items.size()
                    + " 条，请引导用户补充关键字缩小范围");
        }
        return ToolResult.ok(data);
    }
}
