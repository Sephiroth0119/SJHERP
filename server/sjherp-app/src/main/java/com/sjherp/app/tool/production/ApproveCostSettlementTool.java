package com.sjherp.app.tool.production;

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

/**
 * 审核月末成本结转单（DRAFT → APPROVED，M5-T07，HIGH 确认）。
 */
public class ApproveCostSettlementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApproveCostSettlementTool.class);

    private final ProductionCostSettlementAppService settlementAppService;

    public ApproveCostSettlementTool(ProductionCostSettlementAppService settlementAppService) {
        this.settlementAppService = Objects.requireNonNull(settlementAppService, "settlementAppService");
    }

    @Override
    public String name() { return "approve_cost_settlement"; }

    @Override
    public String description() {
        return "审核月末成本结转单（DRAFT → APPROVED）。审核通过后可过账（追加完工工费到产成品 + 出 GL 凭证）。"
                + "必填：doc_no（成本结转单号，PC- 前缀）。";
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
                    "doc_no": { "type": "string", "description": "成本结转单号（PC- 前缀）" }
                  },
                  "required": ["doc_no"]
                }
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        try {
            String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
            if (docNo == null) return ToolResult.fail("doc_no 不能为空");
            String operator = ArchiveToolSupport.operator(context);

            ProductionCostSettlement settlement = settlementAppService.approve(docNo, operator);
            log.info("成本结转单审核成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateCostSettlementTool.toData(settlement));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("成本结转单审核失败：" + e.getMessage());
        }
    }
}
