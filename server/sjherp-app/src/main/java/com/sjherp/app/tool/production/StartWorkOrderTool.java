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
 * 投产（APPROVED → EXECUTING，M5-T07，HIGH 确认）。
 */
public class StartWorkOrderTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(StartWorkOrderTool.class);

    private final TransactionalWorkOrderService workOrderService;

    public StartWorkOrderTool(TransactionalWorkOrderService workOrderService) {
        this.workOrderService = Objects.requireNonNull(workOrderService, "workOrderService");
    }

    @Override
    public String name() { return "start_work_order"; }

    @Override
    public String description() {
        return "投产工单：将已审核工单（APPROVED）推进到执行中（EXECUTING）状态，开始实际生产。"
                + "必填：doc_no（工单单号）。";
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
                    "doc_no": { "type": "string", "description": "工单单号（WO- 前缀）" }
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

            WorkOrder wo = workOrderService.start(docNo, operator);
            log.info("工单投产成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateWorkOrderTool.toData(wo));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("工单投产失败：" + e.getMessage());
        }
    }
}
