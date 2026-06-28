package com.sjherp.app.tool.production;

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

/**
 * 审核领料单（DRAFT → APPROVED，M5-T07，HIGH 确认）。
 */
public class ApproveMaterialIssueTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApproveMaterialIssueTool.class);

    private final MaterialIssueAppService materialIssueAppService;

    public ApproveMaterialIssueTool(MaterialIssueAppService materialIssueAppService) {
        this.materialIssueAppService = Objects.requireNonNull(materialIssueAppService, "materialIssueAppService");
    }

    @Override
    public String name() { return "approve_material_issue"; }

    @Override
    public String description() {
        return "审核领料单（DRAFT → APPROVED）。审核通过后可过账执行实际出库。"
                + "必填：doc_no（领料单号，MI- 前缀）。";
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
                    "doc_no": { "type": "string", "description": "领料单号（MI- 前缀）" }
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

            MaterialIssue issue = materialIssueAppService.approve(docNo, operator);
            log.info("领料单审核成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateMaterialIssueTool.toData(issue));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("领料单审核失败：" + e.getMessage());
        }
    }
}
