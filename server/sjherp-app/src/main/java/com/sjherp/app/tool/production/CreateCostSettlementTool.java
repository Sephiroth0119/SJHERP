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
import com.sjherp.app.production.ProductionCostSettlementAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.production.ProductionCostSettlement;
import com.sjherp.domain.production.ProductionCostSettlementLine;
import com.sjherp.domain.production.ProductionCostSettlementLineInput;

/**
 * 建月末成本结转单（草稿，M5-T07，HIGH 确认）。
 *
 * <p>必填：period（账期 yyyyMM）、lines（每工单一行，含 work_order_doc_no，可选 wip_qty/wip_completion_pct）。
 * 料/工/费三要素金额与完工/在产分摊由领域服务在装载工单/领料/报工/工艺路线后算出，不由调用方传入。
 */
public class CreateCostSettlementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateCostSettlementTool.class);

    private final ProductionCostSettlementAppService settlementAppService;

    public CreateCostSettlementTool(ProductionCostSettlementAppService settlementAppService) {
        this.settlementAppService = Objects.requireNonNull(settlementAppService, "settlementAppService");
    }

    @Override
    public String name() { return "create_cost_settlement"; }

    @Override
    public String description() {
        return "新建月末成本结转单（草稿），按账期归集各工单料/工/费并分摊完工/在产成本。"
                + "必填：period（账期 yyyyMM，如 202606）、lines（结转行数组，每工单一行，含 work_order_doc_no，"
                + "可选 wip_qty 期末在产数量、wip_completion_pct 在产完工程度 0-100）。"
                + "可选：remark（备注）。"
                + "注意：料/工/费金额由系统按约当产量法计算，不需手工传入。";
    }

    @Override
    public ToolRiskLevel riskLevel() { return ToolRiskLevel.HIGH; }

    @Override
    public String requiredPermission() { return "production:cost"; }

    @Override
    public String parameterSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "period": { "type": "string", "description": "账期键 yyyyMM（如 202606）" },
                    "remark": { "type": "string", "description": "备注（可选）" },
                    "lines": {
                      "type": "array",
                      "description": "结转行（每工单一行，至少一行）",
                      "items": {
                        "type": "object",
                        "properties": {
                          "work_order_doc_no":  { "type": "string", "description": "工单号（WO- 前缀，须 EXECUTING/COMPLETED）" },
                          "wip_qty":            { "type": "string", "description": "期末在产数量（BigDecimal，≥ 0，默认 0）" },
                          "wip_completion_pct": { "type": "string", "description": "在产完工程度百分比（BigDecimal，0-100，默认 0）" }
                        },
                        "required": ["work_order_doc_no"]
                      }
                    }
                  },
                  "required": ["period", "lines"]
                }
                """;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String period = ArchiveToolSupport.str(arguments.get("period"));
            if (period == null) return ToolResult.fail("period 不能为空");
            String remark = ArchiveToolSupport.str(arguments.get("remark"));
            String operator = ArchiveToolSupport.operator(context);

            List<Map<String, Object>> rawLines = (List<Map<String, Object>>) arguments.get("lines");
            if (rawLines == null || rawLines.isEmpty()) {
                return ToolResult.fail("结转行（lines）不能为空");
            }
            List<ProductionCostSettlementLineInput> lines = rawLines.stream().map(row -> {
                String workOrderDocNo = ArchiveToolSupport.str(row.get("work_order_doc_no"));
                if (workOrderDocNo == null) throw new IllegalArgumentException("结转行 work_order_doc_no 不能为空");
                BigDecimal wipQty = ProductionToolSupport.decimal(row.get("wip_qty"));
                BigDecimal wipCompletionPct = ProductionToolSupport.decimal(row.get("wip_completion_pct"));
                return new ProductionCostSettlementLineInput(workOrderDocNo, wipQty, wipCompletionPct);
            }).toList();

            ProductionCostSettlement settlement = settlementAppService.create(period, remark, lines, operator);
            log.info("成本结转单建单成功：docNo={}, period={}", settlement.getDocNo(), period);
            return ToolResult.ok(toData(settlement));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("建成本结转单失败：" + e.getMessage());
        }
    }

    static Map<String, Object> toData(ProductionCostSettlement settlement) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("doc_no", settlement.getDocNo());
        data.put("status", settlement.getStatus().name());
        data.put("period", settlement.getPeriod());
        data.put("remark", settlement.getRemark());
        data.put("lines", settlement.getLines().stream().map(CreateCostSettlementTool::lineData).toList());
        data.put("id", settlement.getId());
        return data;
    }

    private static Map<String, Object> lineData(ProductionCostSettlementLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line_no", line.getLineNo());
        m.put("work_order_doc_no", line.getWorkOrderDocNo());
        m.put("material_cost", line.getMaterialCost().toPlainString());
        m.put("labor_cost", line.getLaborCost().toPlainString());
        m.put("overhead_cost", line.getOverheadCost().toPlainString());
        m.put("completed_qty", line.getCompletedQty().toPlainString());
        m.put("completed_cost", line.getCompletedCost().toPlainString());
        m.put("wip_qty", line.getWipQty().toPlainString());
        m.put("wip_completion_pct", line.getWipCompletionPct().toPlainString());
        m.put("wip_cost", line.getWipCost().toPlainString());
        m.put("voucher_doc_no", line.getVoucherDocNo());
        return m;
    }
}
