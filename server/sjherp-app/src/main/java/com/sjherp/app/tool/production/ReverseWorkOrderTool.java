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
 * 冲销已下达工单（APPROVED → REVERSED，M5-T07，HIGH 确认）。
 *
 * <p>已投产（EXECUTING/COMPLETED）工单不可直接冲销，须先退料/冲销领料单后再冲销工单。
 */
public class ReverseWorkOrderTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReverseWorkOrderTool.class);

    private final TransactionalWorkOrderService workOrderService;

    public ReverseWorkOrderTool(TransactionalWorkOrderService workOrderService) {
        this.workOrderService = Objects.requireNonNull(workOrderService, "workOrderService");
    }

    @Override
    public String name() { return "reverse_work_order"; }

    @Override
    public String description() {
        return "冲销已下达工单（APPROVED → REVERSED），不可逆。"
                + "EXECUTING/COMPLETED 工单无法直接冲销——须先冲销关联领料单/生产报告后再执行本操作。"
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

            WorkOrder wo = workOrderService.reverse(docNo, operator);
            log.info("工单冲销成功：docNo={}, operator={}", docNo, operator);
            return ToolResult.ok(CreateWorkOrderTool.toData(wo));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("工单冲销失败：" + e.getMessage());
        }
    }
}
