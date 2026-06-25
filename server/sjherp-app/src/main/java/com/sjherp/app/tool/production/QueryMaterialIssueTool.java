package com.sjherp.app.tool.production;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.production.MaterialIssueAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueQuery;

/**
 * 查询领料单（M5-T07，NORMAL，production:material）。
 *
 * <p>若提供 doc_no 则按单号精确查询；否则按 work_order_doc_no / status 分页搜索。
 */
public class QueryMaterialIssueTool implements Tool {

    private final MaterialIssueAppService materialIssueAppService;

    public QueryMaterialIssueTool(MaterialIssueAppService materialIssueAppService) {
        this.materialIssueAppService = Objects.requireNonNull(materialIssueAppService, "materialIssueAppService");
    }

    @Override
    public String name() { return "query_material_issue"; }

    @Override
    public String description() {
        return "查询领料单。提供 doc_no 精确查询单笔；否则按 work_order_doc_no / status 分页搜索（page/size，默认第 1 页 10 条）。";
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.NORMAL; }

    @Override
    public String requiredPermission() { return "production:material"; }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "doc_no":              { "type": "string", "description": "领料单号（精确查询，MI- 前缀）" },
                    "work_order_doc_no":   { "type": "string", "description": "工单单号过滤（可选）" },
                    "status":              { "type": "string", "description": "状态过滤：DRAFT/APPROVED/POSTED/REVERSED" },
                    "page":                { "type": "integer", "description": "页码（默认 1）" },
                    "size":                { "type": "integer", "description": "每页条数（默认 10，最大 10）" }
                  }
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
            if (docNo != null) {
                MaterialIssue issue = materialIssueAppService.get(docNo);
                return ToolResult.ok(CreateMaterialIssueTool.toData(issue));
            }
            String workOrderDocNo = ArchiveToolSupport.str(arguments.get("work_order_doc_no"));
            DocumentStatus status = parseStatus(arguments.get("status"));
            int page = toInt(arguments.get("page"), 1);
            int size = Math.min(toInt(arguments.get("size"), ArchiveToolSupport.MAX_ITEMS), ArchiveToolSupport.MAX_ITEMS);

            PageResult<MaterialIssue> result = materialIssueAppService.search(new MaterialIssueQuery(workOrderDocNo, status, page, size));
            List<Map<String, Object>> items = result.items().stream()
                    .map(CreateMaterialIssueTool::toData)
                    .toList();
            return ToolResult.ok(Map.of(
                    "total", result.total(),
                    "page", page,
                    "size", size,
                    "items", items));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("查询领料单失败：" + e.getMessage());
        }
    }

    private static DocumentStatus parseStatus(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).strip();
        if (s.isEmpty()) return null;
        try {
            return DocumentStatus.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的状态值：" + s);
        }
    }

    private static int toInt(Object value, int defaultVal) {
        if (value == null) return defaultVal;
        if (value instanceof Number num) return num.intValue();
        try { return Integer.parseInt(String.valueOf(value).strip()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
