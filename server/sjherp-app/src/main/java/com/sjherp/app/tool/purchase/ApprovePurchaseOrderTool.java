package com.sjherp.app.tool.purchase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.purchase.PurchaseOrderAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.purchase.PurchaseOrder;
import com.sjherp.domain.purchase.PurchaseOrderNotFoundException;

/**
 * 审核采购订单工具（M3-T11，HIGH——状态流转不可撤销，框架强制确认卡片）。
 *
 * <p>将采购订单从草稿（DRAFT）推进到已审核（APPROVED），审核后该采购订单可被收货引用。
 * 不动库存、不动资金——仅是确认单据数据无误、允许进入执行环节。
 *
 * <p>写操作经 {@link PurchaseOrderAppService#approve}；审计操作人记 agent:&lt;userId&gt;。
 * 权限点 purchase:order（ADMIN/BOSS/PURCHASER）。
 */
public class ApprovePurchaseOrderTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApprovePurchaseOrderTool.class);

    public static final String NAME = "approve_purchase_order";

    private final PurchaseOrderAppService purchaseOrderAppService;

    public ApprovePurchaseOrderTool(PurchaseOrderAppService purchaseOrderAppService) {
        this.purchaseOrderAppService = Objects.requireNonNull(purchaseOrderAppService,
                "purchaseOrderAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "审核采购订单（DRAFT → APPROVED）：确认采购订单数据无误、允许后续按此单收货。"
                + "审核后该采购订单可被收货单引用，但不产生任何库存或资金变动。"
                + "调用前先在回复正文复述要点（将审核采购订单 <doc_no>）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"采购订单号（如 PO-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "purchase:order";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("采购订单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            PurchaseOrder order = purchaseOrderAppService.approve(docNo, operator);
            log.info("Agent 审核采购订单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(order));
        } catch (PurchaseOrderNotFoundException e) {
            return ToolResult.fail("采购订单不存在: " + docNo);
        } catch (IllegalStateTransitionException e) {
            return ToolResult.fail("采购订单状态流转被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("审核被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", order.getDocNo());
        data.put("status", order.getStatus().name());
        data.put("note", "采购订单已审核，可创建收货单");
        return data;
    }
}
