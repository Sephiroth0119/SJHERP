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
 * 过账领料单（APPROVED → POSTED，执行实际库存出库，M5-T07，HIGH 确认）。
 */
public class PostMaterialIssueTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostMaterialIssueTool.class);

    private final MaterialIssueAppService materialIssueAppService;

    public PostMaterialIssueTool(MaterialIssueAppService materialIssueAppService) {
        this.materialIssueAppService = Objects.requireNonNull(materialIssueAppService, "materialIssueAppService");
    }

    @Override
    public String name() { return "post_material_issue"; }

    @Override
    public String description() {
        return "过账领料单（APPROVED → POSTED），执行实际库存出库，同步更新移动加权成本。"
                + "过账后不可修改，如需撤销须走退料单流程。"
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

            MaterialIssue issue = materialIssueAppService.post(docNo, operator);
            log.info("领料单过账成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateMaterialIssueTool.toData(issue));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("领料单过账失败：" + e.getMessage());
        }
    }
}
