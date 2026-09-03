package com.sjherp.app.tool.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.config.TransactionalWorkOrderService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.production.WorkOrder;

/**
 * 手工建工单（M5-T07，HIGH 确认）。
 *
 * <p>建单参数：product_id、planned_qty、unit_id 为必填；
 * bom_version / routing_version / warehouse_id / planned_start_date /
 * planned_end_date / remark 可选。
 */
public class CreateWorkOrderTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateWorkOrderTool.class);

    private final TransactionalWorkOrderService workOrderService;

    public CreateWorkOrderTool(TransactionalWorkOrderService workOrderService) {
        this.workOrderService = Objects.requireNonNull(workOrderService, "workOrderService");
    }

    @Override
    public String name() { return "create_work_order"; }

    @Override
    public String description() {
        return "手工新建生产任务单（工单），状态为草稿（DRAFT）。"
                + "必填：product_id（产品 ID）、planned_qty（计划数量）、unit_id（计量单位 ID）。"
                + "可选：bom_version（BOM 版本，默认取启用版本）、routing_version（工艺路线版本）、"
                + "warehouse_id（产出仓库 ID）、planned_start_date（计划开工日期 yyyy-MM-dd）、"
                + "planned_end_date（计划完工日期 yyyy-MM-dd）、remark（备注）。";
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.HIGH; }

    @Override
    public String requiredPermission() { return "production:wo"; }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "product_id":          { "type": "integer", "description": "产品 ID" },
                    "planned_qty":         { "type": "string",  "description": "计划生产数量（BigDecimal 字符串）" },
                    "unit_id":             { "type": "integer", "description": "计量单位 ID" },
                    "bom_version":         { "type": "integer", "description": "BOM 版本号（可选，默认取启用版本）" },
                    "routing_version":     { "type": "integer", "description": "工艺路线版本号（可选）" },
                    "warehouse_id":        { "type": "integer", "description": "产出仓库 ID（可选）" },
                    "planned_start_date":  { "type": "string",  "description": "计划开工日期 yyyy-MM-dd（可选）" },
                    "planned_end_date":    { "type": "string",  "description": "计划完工日期 yyyy-MM-dd（可选）" },
                    "remark":              { "type": "string",  "description": "备注（可选）" }
                  },
                  "required": ["product_id", "planned_qty", "unit_id"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            long productId = ProductionToolSupport.longId(arguments.get("product_id"), "product_id");
            BigDecimal plannedQty = ProductionToolSupport.decimal(arguments.get("planned_qty"));
            if (plannedQty == null) {
                return ToolResult.fail("planned_qty 不能为空");
            }
            long unitId = ProductionToolSupport.longId(arguments.get("unit_id"), "unit_id");

            Integer bomVersion = parseVersion(arguments.get("bom_version"));
            Integer routingVersion = parseVersion(arguments.get("routing_version"));
            Long warehouseId = arguments.containsKey("warehouse_id") && arguments.get("warehouse_id") != null
                    ? ProductionToolSupport.longId(arguments.get("warehouse_id"), "warehouse_id")
                    : null;
            LocalDate plannedStartDate = parseDate(arguments.get("planned_start_date"));
            LocalDate plannedEndDate = parseDate(arguments.get("planned_end_date"));
            String remark = ArchiveToolSupport.str(arguments.get("remark"));
            String operator = ArchiveToolSupport.operator(context);

            WorkOrder wo = workOrderService.createManual(productId, plannedQty, unitId,
                    bomVersion, routingVersion, warehouseId,
                    plannedStartDate, plannedEndDate, remark, operator);
            log.info("工单建单成功：docNo={}, productId={}, operator={}", wo.getDocNo(), productId, operator);
            return ToolResult.ok(toData(wo));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("建工单失败：" + e.getMessage());
        }
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).strip();
        if (s.isEmpty()) return null;
        return LocalDate.parse(s);
    }

    private static Integer parseVersion(Object value) {
        if (value == null) return null;
        if (value instanceof Number num) return num.intValue();
        String s = String.valueOf(value).strip();
        if (s.isEmpty()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("版本号必须为整数: " + s);
        }
    }

    static Map<String, Object> toData(WorkOrder wo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc_no", wo.getDocNo());
        data.put("status", wo.getStatus().name());
        data.put("product_id", wo.getProductId());
        data.put("planned_qty", wo.getPlannedQty().toPlainString());
        data.put("unit_id", wo.getUnitId());
        if (wo.getCompletedQty() != null) {
            data.put("completed_qty", wo.getCompletedQty().toPlainString());
        }
        data.put("bom_version", wo.getBomVersion());
        data.put("routing_version", wo.getRoutingVersion());
        data.put("warehouse_id", wo.getWarehouseId());
        data.put("mrp_run_doc_no", wo.getMrpRunDocNo());
        data.put("source_type", wo.getSourceType() != null ? wo.getSourceType().name() : null);
        data.put("planned_start_date", wo.getPlannedStartDate() != null ? wo.getPlannedStartDate().toString() : null);
        data.put("planned_end_date", wo.getPlannedEndDate() != null ? wo.getPlannedEndDate().toString() : null);
        data.put("remark", wo.getRemark());
        data.put("id", wo.getId());
        return data;
    }
}
