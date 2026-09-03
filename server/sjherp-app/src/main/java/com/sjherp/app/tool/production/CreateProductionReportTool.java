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
import com.sjherp.app.production.ProductionReportAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.production.ProductionReport;
import com.sjherp.domain.production.ProductionReportLine;
import com.sjherp.domain.production.ProductionReportLineInput;

/**
 * 建报工单（草稿，M5-T07，HIGH 确认）。
 *
 * <p>必填：work_order_doc_no、warehouse_id、product_id、completed_qty、unit_id、
 * lines（工时行数组，每行含 reported_hours/unit_id，可选工序快照）。
 */
public class CreateProductionReportTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateProductionReportTool.class);

    private final ProductionReportAppService productionReportAppService;

    public CreateProductionReportTool(ProductionReportAppService productionReportAppService) {
        this.productionReportAppService = Objects.requireNonNull(productionReportAppService, "productionReportAppService");
    }

    @Override
    public String name() { return "create_production_report"; }

    @Override
    public String description() {
        return "为工单新建报工单（草稿），记录车间完工与工时。"
                + "必填：work_order_doc_no（关联工单号，工单须为 EXECUTING）、warehouse_id（产成品入库仓库 ID）、"
                + "product_id（生产商品 ID，须与工单一致）、completed_qty（本次合格完工数量，> 0）、"
                + "unit_id（计量单位 ID）、lines（工时行数组，每行含 reported_hours/unit_id，可选"
                + " operation_seq_no/operation_name/work_center/reported_qty）。"
                + "可选：scrap_qty（本次报废数量，≥ 0，默认 0）、remark（备注）。";
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.HIGH; }

    @Override
    public String requiredPermission() { return "production:report"; }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "work_order_doc_no": { "type": "string",  "description": "关联工单号（WO- 前缀，须 EXECUTING）" },
                    "warehouse_id":      { "type": "integer", "description": "产成品入库仓库 ID" },
                    "product_id":        { "type": "integer", "description": "生产商品 ID（须与工单一致）" },
                    "completed_qty":     { "type": "string",  "description": "本次合格完工数量（BigDecimal，> 0）" },
                    "scrap_qty":         { "type": "string",  "description": "本次报废数量（BigDecimal，≥ 0，默认 0）" },
                    "unit_id":           { "type": "integer", "description": "计量单位 ID" },
                    "remark":            { "type": "string",  "description": "备注（可选）" },
                    "lines": {
                      "type": "array",
                      "description": "工时行（至少一行）",
                      "items": {
                        "type": "object",
                        "properties": {
                          "operation_seq_no": { "type": "integer", "description": "工序序号快照（可选）" },
                          "operation_name":   { "type": "string",  "description": "工序名称快照（可选）" },
                          "work_center":      { "type": "string",  "description": "工作中心快照（可选）" },
                          "reported_hours":   { "type": "string",  "description": "报工工时（BigDecimal，> 0）" },
                          "reported_qty":     { "type": "string",  "description": "报工数量（BigDecimal，可选，默认 = completed_qty）" },
                          "unit_id":          { "type": "integer", "description": "计量单位 ID" }
                        },
                        "required": ["reported_hours", "unit_id"]
                      }
                    }
                  },
                  "required": ["work_order_doc_no", "warehouse_id", "product_id", "completed_qty", "unit_id", "lines"]
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
            long productId = ProductionToolSupport.longId(arguments.get("product_id"), "product_id");
            BigDecimal completedQty = ProductionToolSupport.decimal(arguments.get("completed_qty"));
            if (completedQty == null) return ToolResult.fail("completed_qty 不能为空");
            BigDecimal scrapQty = ProductionToolSupport.decimal(arguments.get("scrap_qty"));
            long unitId = ProductionToolSupport.longId(arguments.get("unit_id"), "unit_id");
            String remark = ArchiveToolSupport.str(arguments.get("remark"));
            String operator = ArchiveToolSupport.operator(context);

            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) arguments.get("lines");
            if (rawLines == null || rawLines.isEmpty()) {
                return ToolResult.fail("工时行（lines）不能为空");
            }
            List<ProductionReportLineInput> lines = rawLines.stream().map(row -> {
                Integer operationSeqNo = ProductionToolSupport.intVal(row.get("operation_seq_no"));
                String operationName = ArchiveToolSupport.str(row.get("operation_name"));
                String workCenter = ArchiveToolSupport.str(row.get("work_center"));
                BigDecimal reportedHours = ProductionToolSupport.decimal(row.get("reported_hours"));
                if (reportedHours == null) throw new IllegalArgumentException("工时行 reported_hours 不能为空");
                BigDecimal reportedQty = ProductionToolSupport.decimal(row.get("reported_qty"));
                long lineUnitId = ProductionToolSupport.longId(row.get("unit_id"), "unit_id");
                return new ProductionReportLineInput(operationSeqNo, operationName, workCenter,
                        reportedHours, reportedQty, lineUnitId);
            }).toList();

            ProductionReport report = productionReportAppService.create(workOrderDocNo, warehouseId,
                    productId, completedQty, scrapQty, unitId, remark, lines, operator);
            log.info("报工单建单成功：docNo={}, workOrderDocNo={}", report.getDocNo(), workOrderDocNo);
            return ToolResult.ok(toData(report));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("建报工单失败：" + e.getMessage());
        }
    }

    static Map<String, Object> toData(ProductionReport report) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc_no", report.getDocNo());
        data.put("status", report.getStatus().name());
        data.put("work_order_doc_no", report.getWorkOrderDocNo());
        data.put("warehouse_id", report.getWarehouseId());
        data.put("product_id", report.getProductId());
        data.put("completed_qty", report.getCompletedQty().toPlainString());
        data.put("scrap_qty", report.getScrapQty().toPlainString());
        data.put("unit_id", report.getUnitId());
        if (report.getInboundCost() != null) {
            data.put("inbound_cost", report.getInboundCost().toPlainString());
        }
        data.put("remark", report.getRemark());
        data.put("lines", report.getLines().stream().map(CreateProductionReportTool::lineData).toList());
        data.put("id", report.getId());
        return data;
    }

    private static Map<String, Object> lineData(ProductionReportLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line_no", line.getLineNo());
        m.put("operation_seq_no", line.getOperationSeqNo());
        m.put("operation_name", line.getOperationName());
        m.put("work_center", line.getWorkCenter());
        m.put("reported_hours", line.getReportedHours().toPlainString());
        m.put("reported_qty", line.getReportedQty() != null ? line.getReportedQty().toPlainString() : null);
        m.put("unit_id", line.getUnitId());
        return m;
    }
}
