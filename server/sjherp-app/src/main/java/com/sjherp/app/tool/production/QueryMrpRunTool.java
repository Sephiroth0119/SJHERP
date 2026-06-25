package com.sjherp.app.tool.production;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.config.TransactionalMrpService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpSuggestion;

/**
 * 查询 MRP 运行结果（M5-T07，NORMAL，production:mrp）。
 *
 * <p>若提供 doc_no 则按单号精确查询（含建议明细）；否则分页查询历史运行（仅头信息，不含建议行）。
 */
public class QueryMrpRunTool implements Tool {

    private final TransactionalMrpService mrpService;

    public QueryMrpRunTool(TransactionalMrpService mrpService) {
        this.mrpService = Objects.requireNonNull(mrpService, "mrpService");
    }

    @Override
    public String name() { return "query_mrp_run"; }

    @Override
    public String description() {
        return "查询 MRP 运行结果。提供 doc_no 精确查询单次运行（含生产/采购建议明细）；"
                + "否则分页查询历史运行列表（仅头信息，不含建议明细，page/size，默认第 1 页 10 条）。";
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.NORMAL; }

    @Override
    public String requiredPermission() { return "production:mrp"; }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "doc_no": { "type": "string",  "description": "MRP 运行单号（精确查询，MRP- 前缀）" },
                    "page":   { "type": "integer", "description": "页码（默认 1）" },
                    "size":   { "type": "integer", "description": "每页条数（默认 10，最大 " + ArchiveToolSupport.MAX_ITEMS + "）" }
                  }
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
            if (docNo != null) {
                MrpRun run = mrpService.get(docNo);
                return ToolResult.ok(toData(run, true));
            }
            int page = toInt(arguments.get("page"), 1);
            int size = Math.min(toInt(arguments.get("size"), ArchiveToolSupport.MAX_ITEMS), ArchiveToolSupport.MAX_ITEMS);

            PageResult<MrpRun> result = mrpService.searchHistory(page, size);
            List<Map<String, Object>> items = result.items().stream()
                    .map(run -> toData(run, false))
                    .toList();
            return ToolResult.ok(Map.of(
                    "total", result.total(),
                    "page", page,
                    "size", size,
                    "items", items));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("查询 MRP 运行失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> toData(MrpRun run, boolean includeSuggestions) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc_no", run.getDocNo());
        data.put("run_at", run.getRunAt().toString());
        data.put("warehouse_id", run.getWarehouseId());
        data.put("include_forecast", run.isIncludeForecast());
        data.put("include_sales_order", run.isIncludeSalesOrder());
        data.put("remark", run.getRemark());
        data.put("created_by", run.getCreatedBy());
        data.put("id", run.getId());
        if (includeSuggestions) {
            data.put("suggestions", run.getSuggestions().stream()
                    .map(QueryMrpRunTool::suggestionData)
                    .toList());
        }
        return data;
    }

    private static Map<String, Object> suggestionData(MrpSuggestion s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.type().name());
        m.put("product_id", s.productId());
        m.put("level", s.level());
        m.put("gross_requirement", s.grossRequirement().toPlainString());
        m.put("on_hand", s.onHand().toPlainString());
        m.put("net_requirement", s.netRequirement().toPlainString());
        m.put("base_unit_id", s.baseUnitId());
        return m;
    }

    private static int toInt(Object value, int defaultVal) {
        if (value == null) return defaultVal;
        if (value instanceof Number num) return num.intValue();
        try { return Integer.parseInt(String.valueOf(value).strip()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
