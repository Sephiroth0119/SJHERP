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
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptNotFoundException;

/**
 * 审核采购入库单工具（M3-T11，HIGH——状态流转，框架强制确认卡片）：将采购入库单从草稿（DRAFT）
 * 推进至已审核（APPROVED），审核后方可过账真正入库。
 *
 * <p>写操作经 {@link PurchaseReceiptAppService#approve}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 purchase:receipt（ADMIN/BOSS/WAREHOUSE）。
 */
public class ApprovePurchaseReceiptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApprovePurchaseReceiptTool.class);

    public static final String NAME = "approve_purchase_receipt";

    private final PurchaseReceiptAppService purchaseReceiptAppService;

    public ApprovePurchaseReceiptTool(PurchaseReceiptAppService purchaseReceiptAppService) {
        this.purchaseReceiptAppService = Objects.requireNonNull(purchaseReceiptAppService,
                "purchaseReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "审核采购入库单（DRAFT → APPROVED）：确认收货单数据无误、允许后续过账入库。"
                + "审核不产生库存变动，过账才真正入库。"
                + "调用前先在回复正文复述要点（将审核采购入库单 <doc_no>）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"采购入库单号（如 PR-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "purchase:receipt";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("采购入库单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            PurchaseReceipt receipt = purchaseReceiptAppService.approve(docNo, operator);
            log.info("Agent 审核采购入库单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(receipt));
        } catch (PurchaseReceiptNotFoundException e) {
            return ToolResult.fail("采购入库单不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("审核采购入库单被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("审核被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("purchaseOrderNo", receipt.getPurchaseOrderNo());
        data.put("totalAmount", receipt.totalAmount().toPlainString());
        data.put("note", "采购入库单已审核，可进行过账（过账后真正入库，不可撤销）");
        return data;
    }
}
