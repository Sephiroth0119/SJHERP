package com.sjherp.app.tool;

import java.time.Instant;
import java.util.Map;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;

/**
 * 演示工具：模拟"单据过账"高风险操作（M1-T03 验收用）。
 *
 * <p>riskLevel=HIGH：Agent 执行循环在框架层强制拦截——未经用户点击确认卡片
 * 一律不执行（Human-in-the-loop，不靠提示词自觉）。仅在 dev / local profile
 * 注册（{@code DemoToolConfig}）。<b>不写任何真实数据</b>，只返回模拟结果，
 * 用于端到端演示：触发 → 确认卡片 → 确认 → 执行 → 模型引用结果回答。
 */
public class DemoHighRiskTool implements Tool {

    @Override
    public String name() {
        return "demo_post_document";
    }

    @Override
    public String description() {
        return "演示版单据过账工具（高风险）：把指定编号的单据标记为已过账。"
                + "当用户要求过账/审核某张单据时调用。注意：这是演示工具，不会修改真实数据。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "documentId":{"type":"string","description":"要过账的单据编号，如 PO-202606-0001"},\
                "remark":{"type":"string","description":"过账备注，可选"}},\
                "required":["documentId"]}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        // M2-T06 起真实生效（RolePermissionToolChecker）：矩阵中仅 ADMIN 持有该演示权限点
        return "demo:post_document";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String documentId = String.valueOf(arguments.get("documentId"));
        // 演示实现：不触碰任何真实业务表，只返回模拟的过账结果
        return ToolResult.ok(Map.of(
                "documentId", documentId,
                "status", "POSTED",
                "postedAt", Instant.now().toString(),
                "operator", context.userId(),
                "demo", true,
                "note", "演示工具：未修改任何真实数据"));
    }
}
