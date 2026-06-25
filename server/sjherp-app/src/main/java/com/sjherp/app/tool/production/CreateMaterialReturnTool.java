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
import com.sjherp.app.production.MaterialReturnAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.production.MaterialReturn;
import com.sjherp.domain.production.MaterialReturnLine;
import com.sjherp.domain.production.MaterialReturnLineInput;

/**
 * 建退料单（草稿，M5-T07，HIGH 确认）。
 *
 * <p>必填：material_issue_doc_no、warehouse_id、lines（数组，每行含 product_id/quantity/unit_id，
 * 可选 src_issue_line_no）。
 */
public class CreateMaterialReturnTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateMaterialReturnTool.class);

    private final MaterialReturnAppService materialReturnAppService;

    public CreateMaterialReturnTool(MaterialReturnAppService materialReturnAppService) {
        this.materialReturnAppService = Objects.requireNonNull(materialReturnAppService, "materialReturnAppService");
    }

    @Override
    public String name() { return "create_material_return"; }

    @Override
    public String description() {
        return "为已过账领料单新建退料单（草稿）。按原领料成本退回库存。"
                + "必填：material_issue_doc_no（原领料单号，MI- 前缀）、warehouse_id（退料仓库 ID）、"
                + "lines（退料行数组，每行含 product_id/quantity/unit_id，可选 src_issue_line_no）。"
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
                    "material_issue_doc_no": { "type": "string",  "description": "原领料单号（MI- 前缀）" },
                    "warehouse_id":          { "type": "integer", "description": "退料仓库 ID" },
                    "remark":                { "type": "string",  "description": "备注（可选）" },
                    "lines": {
                      "type": "array",
                      "description": "退料行（至少一行）",
                      "items": {
                        "type": "object",
                        "properties": {
                          "product_id":        { "type": "integer", "description": "子件商品 ID" },
                          "quantity":          { "type": "string",  "description": "退料数量（BigDecimal，> 0）" },
                          "unit_id":           { "type": "integer", "description": "计量单位 ID" },
                          "src_issue_line_no": { "type": "integer", "description": "原领料单行号（可选，追溯用）" }
                        },
                        "required": ["product_id", "quantity", "unit_id"]
                      }
                    }
                  },
                  "required": ["material_issue_doc_no", "warehouse_id", "lines"]
                }
                """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String materialIssueDocNo = ArchiveToolSupport.str(arguments.get("material_issue_doc_no"));
            if (materialIssueDocNo == null) return ToolResult.fail("material_issue_doc_no 不能为空");

            long warehouseId = ProductionToolSupport.longId(arguments.get("warehouse_id"), "warehouse_id");
            String remark = ArchiveToolSupport.str(arguments.get("remark"));
            String operator = ArchiveToolSupport.operator(context);

            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) arguments.get("lines");
            if (rawLines == null || rawLines.isEmpty()) {
                return ToolResult.fail("退料行（lines）不能为空");
            }
            List<MaterialReturnLineInput> lines = rawLines.stream().map(row -> {
                long productId = ProductionToolSupport.longId(row.get("product_id"), "product_id");
                BigDecimal quantity = ProductionToolSupport.decimal(row.get("quantity"));
                if (quantity == null) throw new IllegalArgumentException("退料行 quantity 不能为空");
                long unitId = ProductionToolSupport.longId(row.get("unit_id"), "unit_id");
                Integer srcIssueLineNo = row.get("src_issue_line_no") != null
                        ? ProductionToolSupport.intVal(row.get("src_issue_line_no")) : null;
                return new MaterialReturnLineInput(productId, quantity, unitId, srcIssueLineNo);
            }).toList();

            MaterialReturn ret = materialReturnAppService.create(materialIssueDocNo, warehouseId, remark, lines, operator);
            log.info("退料单建单成功：docNo={}, materialIssueDocNo={}", ret.getDocNo(), materialIssueDocNo);
            return ToolResult.ok(toData(ret));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("建退料单失败：" + e.getMessage());
        }
    }

    static Map<String, Object> toData(MaterialReturn ret) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc_no", ret.getDocNo());
        data.put("status", ret.getStatus().name());
        data.put("material_issue_doc_no", ret.getMaterialIssueDocNo());
        data.put("warehouse_id", ret.getWarehouseId());
        data.put("remark", ret.getRemark());
        data.put("total_returned_cost", ret.totalReturnedCost().toPlainString());
        data.put("lines", ret.getLines().stream().map(CreateMaterialReturnTool::lineData).toList());
        data.put("id", ret.getId());
        return data;
    }

    private static Map<String, Object> lineData(MaterialReturnLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line_no", line.getLineNo());
        m.put("product_id", line.getProductId());
        m.put("quantity", line.getQuantity().toPlainString());
        m.put("unit_id", line.getUnitId());
        m.put("src_issue_line_no", line.getSrcIssueLineNo());
        if (line.getReturnedCost() != null) m.put("returned_cost", line.getReturnedCost().toPlainString());
        return m;
    }
}
