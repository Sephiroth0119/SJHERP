package com.sjherp.app.tool.production;

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

/**
 * 过账退料单（APPROVED → COMPLETED，执行库存入库，M5-T07，HIGH 确认）。
 */
public class PostMaterialReturnTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostMaterialReturnTool.class);

    private final MaterialReturnAppService materialReturnAppService;

    public PostMaterialReturnTool(MaterialReturnAppService materialReturnAppService) {
        this.materialReturnAppService = Objects.requireNonNull(materialReturnAppService, "materialReturnAppService");
    }

    @Override
    public String name() { return "post_material_return"; }

    @Override
    public String description() {
        return "过账退料单（APPROVED → COMPLETED），按原领料成本执行 PRODUCTION_RETURN 入库，更新移动加权成本。"
                + "过账后不可修改。"
                + "必填：doc_no（退料单号，MR- 前缀）。";
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
                    "doc_no": { "type": "string", "description": "退料单号（MR- 前缀）" }
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

            MaterialReturn ret = materialReturnAppService.post(docNo, operator);
            log.info("退料单过账成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateMaterialReturnTool.toData(ret));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("退料单过账失败：" + e.getMessage());
        }
    }
}
