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
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryNotFoundException;

/**
 * 审核销售出库单工具（M3-T11，HIGH——状态流转，框架强制确认卡片）：将销售出库单从草稿（DRAFT）
 * 推进至已审核（APPROVED），审核后方可过账真正扣减库存。
 *
 * <p>写操作经 {@link SalesDeliveryAppService#approve}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 sales:delivery（ADMIN/BOSS/WAREHOUSE）。
 */
public class ApproveSalesDeliveryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApproveSalesDeliveryTool.class);

    public static final String NAME = "approve_sales_delivery";

    private final SalesDeliveryAppService salesDeliveryAppService;

    public ApproveSalesDeliveryTool(SalesDeliveryAppService salesDeliveryAppService) {
        this.salesDeliveryAppService = Objects.requireNonNull(salesDeliveryAppService,
                "salesDeliveryAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "审核销售出库单（DRAFT → APPROVED）：确认出库单数据无误、允许后续过账真正扣减库存。"
                + "审核不产生库存变动，过账才真正出库并计算成本（COGS）。"
                + "调用前先在回复正文复述要点（将审核销售出库单 <doc_no>）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售出库单号（如 SD-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "sales:delivery";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("销售出库单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            SalesDelivery delivery = salesDeliveryAppService.approve(docNo, operator);
            log.info("Agent 审核销售出库单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(delivery));
        } catch (SalesDeliveryNotFoundException e) {
            return ToolResult.fail("销售出库单不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("审核销售出库单被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("审核被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesDelivery delivery) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", delivery.getDocNo());
        data.put("status", delivery.getStatus().name());
        data.put("salesOrderNo", delivery.getSalesOrderNo());
        data.put("note", "销售出库单已审核，可进行过账（过账后真正扣库存、算成本，不可撤销）");
        return data;
    }
}
