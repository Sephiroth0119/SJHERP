package com.sjherp.app.tool.production;

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
import com.sjherp.app.production.MaterialIssueAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLine;
import com.sjherp.domain.production.MaterialIssueLineInput;

/**
 * 建领料单（M5-T07，HIGH 确认）。
 *
 * <p>必填：work_order_doc_no、warehouse_id、lines（数组，每行含 product_id/required_qty/quantity/unit_id）。
 */
public class CreateMaterialIssueTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateMaterialIssueTool.class);

    private final MaterialIssueAppService materialIssueAppService;

    public CreateMaterialIssueTool(MaterialIssueAppService materialIssueAppService) {
        this.materialIssueAppService = Objects.requireNonNull(materialIssueAppService, "materialIssueAppService");
    }

    @Override
    public String name() { return "create_material_issue"; }

    @Override
    public String description() {
        return "为工单新建领料单（草稿）。"
                + "必填：work_order_doc_no（工单单号）、warehouse_id（领料仓库 ID）、"
                + "lines（领料行数组，每行含 product_id/required_qty/quantity/unit_id）。"
                + "可选：remark（备注）。";
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.HIGH; }

    @Override
    public String requiredPermission() { return "production:material"; }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "work_order_doc_no": { "type": "string",  "description": "工单单号（WO- 前缀）" },
                    "warehouse_id":      { "type": "integer", "description": "领料仓库 ID" },
                    "remark":            { "type": "string",  "description": "备注（可选）" },
                    "lines": {
                      "type": "array",
                      "description": "领料行（至少一行）",
                      "items": {
                        "type": "object",
                        "properties": {
                          "product_id":   { "type": "integer", "description": "物料产品 ID" },
                          "required_qty": { "type": "string",  "description": "BOM 需求数量（BigDecimal）" },
                          "quantity":     { "type": "string",  "description": "实际领料数量（BigDecimal）" },
                          "unit_id":      { "type": "integer", "description": "计量单位 ID" }
                        },
                        "required": ["product_id", "quantity", "unit_id"]
                      }
                    }
                  },
                  "required": ["work_order_doc_no", "warehouse_id", "lines"]
                }
                """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String workOrderDocNo = ArchiveToolSupport.str(arguments.get("work_order_doc_no"));
            if (workOrderDocNo == null) return ToolResult.fail("work_order_doc_no 不能为空");

            long warehouseId = ProductionToolSupport.longId(arguments.get("warehouse_id"), "warehouse_id");
            String remark = ArchiveToolSupport.str(arguments.get("remark"));
            String operator = ArchiveToolSupport.operator(context);

            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) arguments.get("lines");
            if (rawLines == null || rawLines.isEmpty()) {
                return ToolResult.fail("领料行（lines）不能为空");
            }
            List<MaterialIssueLineInput> lines = rawLines.stream().map(row -> {
                long productId = ProductionToolSupport.longId(row.get("product_id"), "product_id");
                BigDecimal requiredQty = ProductionToolSupport.decimal(row.get("required_qty"));
                BigDecimal quantity = ProductionToolSupport.decimal(row.get("quantity"));
                if (quantity == null) throw new IllegalArgumentException("领料行 quantity 不能为空");
                long unitId = ProductionToolSupport.longId(row.get("unit_id"), "unit_id");
                return new MaterialIssueLineInput(productId, requiredQty, quantity, unitId);
            }).toList();

            MaterialIssue issue = materialIssueAppService.create(workOrderDocNo, warehouseId, remark, lines, operator);
            log.info("领料单建单成功：docNo={}, workOrderDocNo={}", issue.getDocNo(), workOrderDocNo);
            return ToolResult.ok(toData(issue));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("建领料单失败：" + e.getMessage());
        }
    }

    static Map<String, Object> toData(MaterialIssue issue) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc_no", issue.getDocNo());
        data.put("status", issue.getStatus().name());
        data.put("work_order_doc_no", issue.getWorkOrderDocNo());
        data.put("warehouse_id", issue.getWarehouseId());
        data.put("remark", issue.getRemark());
        data.put("total_issued_cost", issue.totalIssuedCost().toPlainString());
        data.put("lines", issue.getLines().stream().map(CreateMaterialIssueTool::lineData).toList());
        data.put("id", issue.getId());
        return data;
    }

    private static Map<String, Object> lineData(MaterialIssueLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line_no", line.getLineNo());
        m.put("product_id", line.getProductId());
        m.put("required_qty", line.getRequiredQty() != null ? line.getRequiredQty().toPlainString() : null);
        m.put("quantity", line.getQuantity().toPlainString());
        m.put("unit_id", line.getUnitId());
        if (line.getIssuedCost() != null) m.put("issued_cost", line.getIssuedCost().toPlainString());
        return m;
    }
}
