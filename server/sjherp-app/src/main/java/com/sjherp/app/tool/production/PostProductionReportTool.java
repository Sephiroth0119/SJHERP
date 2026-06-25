package com.sjherp.app.tool.production;

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

/**
 * 过账报工单（APPROVED → COMPLETED，完工入库，M5-T07，HIGH 确认）。
 */
public class PostProductionReportTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostProductionReportTool.class);

    private final ProductionReportAppService productionReportAppService;

    public PostProductionReportTool(ProductionReportAppService productionReportAppService) {
        this.productionReportAppService = Objects.requireNonNull(productionReportAppService, "productionReportAppService");
    }

    @Override
    public String name() { return "post_production_report"; }

    @Override
    public String description() {
        return "过账报工单（APPROVED → COMPLETED），执行 PRODUCTION_IN 完工入库，结转工单已领料成本至产成品。"
                + "工单无已过账领料成本时整批回滚。过账后不可修改。"
                + "必填：doc_no（报工单号，PR- 前缀）。";
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
                    "doc_no": { "type": "string", "description": "报工单号（PR- 前缀）" }
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

            ProductionReport report = productionReportAppService.post(docNo, operator);
            log.info("报工单过账成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateProductionReportTool.toData(report));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("报工单过账失败：" + e.getMessage());
        }
    }
}
