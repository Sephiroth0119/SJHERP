package com.sjherp.app.tool.warehouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseService;

/**
 * 仓库档案查询工具（M2-T08，NORMAL）：关键字 + 状态过滤，返回精简列表（最多 10 条）。
 * 查询经 {@link WarehouseService} 唯一入口。
 */
public class SearchWarehousesTool implements Tool {

    public static final String NAME = "search_warehouses";

    private final WarehouseService warehouseService;

    public SearchWarehousesTool(WarehouseService warehouseService) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询仓库档案：按关键字（模糊匹配编码/名称/负责人）和状态过滤，"
                + "返回精简列表（编码/名称/地址/负责人/状态，最多 10 条）。"
                + "用户问\"有哪些仓库\"\"查一下某仓库\"时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "keyword":{"type":"string","description":"关键字，模糊匹配仓库编码/名称/负责人；不传表示不按关键字过滤"},\
                "status":{"type":"string","enum":["ENABLED","DISABLED"],\
                "description":"档案状态过滤：ENABLED 启用 / DISABLED 停用；不传表示全部"}},\
                "required":[],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        PageResult<Warehouse> result = warehouseService.search(new WarehouseQuery(
                ArchiveToolSupport.str(arguments.get("keyword")),
                ArchiveToolSupport.parseStatus(arguments.get("status")),
                1, ArchiveToolSupport.MAX_ITEMS));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Warehouse warehouse : result.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", warehouse.getId());
            item.put("code", warehouse.getCode());
            item.put("name", warehouse.getName());
            item.put("address", warehouse.getAddress());
            item.put("manager", warehouse.getManager());
            item.put("status", ArchiveToolSupport.statusLabel(warehouse.getStatus()));
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
