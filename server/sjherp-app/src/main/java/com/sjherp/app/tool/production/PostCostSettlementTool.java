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
 * 过账月末成本结转单（APPROVED → COMPLETED，库存 CostAdjust + GL 凭证，M5-T07，HIGH 确认）。
 */
public class PostCostSettlementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostCostSettlementTool.class);

    private final ProductionCostSettlementAppService settlementAppService;

    public PostCostSettlementTool(ProductionCostSettlementAppService settlementAppService) {
        this.settlementAppService = Objects.requireNonNull(settlementAppService, "settlementAppService");
    }

    @Override
    public String name() { return "post_cost_settlement"; }

    @Override
    public String description() {
        return "过账月末成本结转单（APPROVED → COMPLETED），逐工单追加完工工费增量到产成品仓 +"
                + " 出 GL 凭证（料/工费归集 + 完工结转），同一事务原子。账期 CLOSED 时整批回滚。过账后不可修改。"
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

            ProductionCostSettlement settlement = settlementAppService.post(docNo, operator);
            log.info("成本结转单过账成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateCostSettlementTool.toData(settlement));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("成本结转单过账失败：" + e.getMessage());
        }
    }
}
