package com.sjherp.app.tool.partner;

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
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierQuery;
import com.sjherp.domain.partner.SupplierService;

/**
 * 供应商档案查询工具（M2-T08，NORMAL）：关键字 + 状态过滤，返回精简列表（最多 10 条）。
 * 查询经 {@link SupplierService} 唯一入口。
 */
public class SearchSuppliersTool implements Tool {

    public static final String NAME = "search_suppliers";

    private final SupplierService supplierService;

    public SearchSuppliersTool(SupplierService supplierService) {
        this.supplierService = Objects.requireNonNull(supplierService, "supplierService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询供应商档案：按关键字（模糊匹配编码/名称/联系人/电话）和状态过滤，"
                + "返回精简列表（编码/名称/联系人/电话/结算方式/状态，最多 10 条）。"
                + "用户问\"有哪些供应商\"\"查一下某供应商\"时调用。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "keyword":{"type":"string","description":"关键字，模糊匹配供应商编码/名称/联系人/电话；不传表示不按关键字过滤"},\
                "status":{"type":"string","enum":["ENABLED","DISABLED"],\
                "description":"档案状态过滤：ENABLED 启用 / DISABLED 停用；不传表示全部"}},\
                "required":[],"additionalProperties":false}""";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        PageResult<Supplier> result = supplierService.search(new SupplierQuery(
                ArchiveToolSupport.str(arguments.get("keyword")),
                ArchiveToolSupport.parseStatus(arguments.get("status")),
                1, ArchiveToolSupport.MAX_ITEMS));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Supplier supplier : result.items()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", supplier.getId());
            item.put("code", supplier.getCode());
            item.put("name", supplier.getName());
            item.put("contactPerson", supplier.getContactPerson());
            item.put("contactPhone", supplier.getContactPhone());
            item.put("settlementMethod", ArchiveToolSupport.settlementLabel(supplier.getSettlementMethod()));
            item.put("status", ArchiveToolSupport.statusLabel(supplier.getStatus()));
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
