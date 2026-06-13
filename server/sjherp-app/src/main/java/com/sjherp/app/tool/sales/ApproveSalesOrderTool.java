package com.sjherp.app.tool.sales;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.sales.SalesOrderAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.sales.SalesOrder;
import com.sjherp.domain.sales.SalesOrderNotFoundException;

/**
 * 审核销售订单工具（M3-T11，HIGH——状态流转，框架强制确认卡片）：将销售订单从草稿（DRAFT）
 * 推进至已审核（APPROVED），审核后该销售订单可被出库单引用。
 *
 * <p>审核只确认数据无误，不动库存、不动钱。
 * 写操作经 {@link SalesOrderAppService#approve}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 sales:order（ADMIN/BOSS/SALES）。
 */
public class ApproveSalesOrderTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApproveSalesOrderTool.class);

    public static final String NAME = "approve_sales_order";

    private final SalesOrderAppService salesOrderAppService;

    public ApproveSalesOrderTool(SalesOrderAppService salesOrderAppService) {
        this.salesOrderAppService = Objects.requireNonNull(salesOrderAppService,
                "salesOrderAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "审核销售订单（DRAFT → APPROVED）：确认销售订单数据无误、允许后续按此单出库发货。"
                + "审核后该销售订单可被出库单引用，但不产生任何库存或资金变动。"
                + "调用前先在回复正文复述要点（将审核销售订单 <doc_no>）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售订单号（如 SO-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "sales:order";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("销售订单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            SalesOrder order = salesOrderAppService.approve(docNo, operator);
            log.info("Agent 审核销售订单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(order));
        } catch (SalesOrderNotFoundException e) {
            return ToolResult.fail("销售订单不存在: " + docNo);
        } catch (IllegalStateTransitionException e) {
            return ToolResult.fail("销售订单状态流转被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("审核被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", order.getDocNo());
        data.put("status", order.getStatus().name());
        data.put("note", "销售订单已审核，可创建出库单");
        return data;
    }
}
