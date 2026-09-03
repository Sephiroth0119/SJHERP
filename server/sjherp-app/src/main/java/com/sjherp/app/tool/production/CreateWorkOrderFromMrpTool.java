package com.sjherp.app.tool.production;

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
 * 从 MRP 建议转工单（M5-T07，HIGH 确认）。
 *
 * <p>必填：mrp_run_doc_no（MRP 运行单号）、product_id（产品 ID）。
 * 领域服务按 mrpRunDocNo + productId 查 PRODUCTION 建议取 netRequirement → plannedQty。
 */
public class CreateWorkOrderFromMrpTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateWorkOrderFromMrpTool.class);

    private final TransactionalWorkOrderService workOrderService;

    public CreateWorkOrderFromMrpTool(TransactionalWorkOrderService workOrderService) {
        this.workOrderService = Objects.requireNonNull(workOrderService, "workOrderService");
    }

    @Override
    public String name() { return "create_work_order_from_mrp"; }

    @Override
    public String description() {
        return "从 MRP 运行建议转建工单（DRAFT 草稿）。"
                + "必填：mrp_run_doc_no（MRP 运行单号，MRP-YYYYMM-NNNN 格式）、"
                + "product_id（要转单的产品 ID，须在 MRP 结果中有生产建议）。";
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
                    "mrp_run_doc_no": { "type": "string",  "description": "MRP 运行单号" },
                    "product_id":     { "type": "integer", "description": "产品 ID（须在 MRP 建议中存在 PRODUCTION 类型建议）" }
                  },
                  "required": ["mrp_run_doc_no", "product_id"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String mrpRunDocNo = ArchiveToolSupport.str(arguments.get("mrp_run_doc_no"));
            if (mrpRunDocNo == null) {
                return ToolResult.fail("mrp_run_doc_no 不能为空");
            }
            long productId = ProductionToolSupport.longId(arguments.get("product_id"), "product_id");
            String operator = ArchiveToolSupport.operator(context);

            WorkOrder wo = workOrderService.createFromSuggestion(mrpRunDocNo, productId, operator);
            log.info("MRP 转工单成功：docNo={}, mrpRun={}, productId={}", wo.getDocNo(), mrpRunDocNo, productId);
            return ToolResult.ok(CreateWorkOrderTool.toData(wo));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("MRP 转工单失败：" + e.getMessage());
        }
    }
}
