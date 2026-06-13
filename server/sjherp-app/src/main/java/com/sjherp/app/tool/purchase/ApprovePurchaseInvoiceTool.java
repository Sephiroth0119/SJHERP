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
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceNotFoundException;

/**
 * 审核采购发票工具（M3-T11，HIGH——状态流转，框架强制确认卡片）：将采购发票从草稿（DRAFT）
 * 推进至已审核（APPROVED），审核后方可过账生成应付账款。
 *
 * <p>写操作经 {@link PurchaseInvoiceAppService#approve}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 purchase:invoice（ADMIN/BOSS/ACCOUNTANT）。
 */
public class ApprovePurchaseInvoiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApprovePurchaseInvoiceTool.class);

    public static final String NAME = "approve_purchase_invoice";

    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public ApprovePurchaseInvoiceTool(PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.purchaseInvoiceAppService = Objects.requireNonNull(purchaseInvoiceAppService,
                "purchaseInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "审核采购发票（DRAFT → APPROVED）：确认发票数据无误、允许后续过账生成应付账款。"
                + "审核不产生应付，过账才真正生成应付。"
                + "调用前先在回复正文复述要点（将审核采购发票 <doc_no>）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"采购发票号（如 PINV-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "purchase:invoice";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("采购发票号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            PurchaseInvoice invoice = purchaseInvoiceAppService.approve(docNo, operator);
            log.info("Agent 审核采购发票（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(invoice));
        } catch (PurchaseInvoiceNotFoundException e) {
            return ToolResult.fail("采购发票不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("审核采购发票被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("审核被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseInvoice invoice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", invoice.getDocNo());
        data.put("status", invoice.getStatus().name());
        data.put("purchaseReceiptNo", invoice.getPurchaseReceiptNo());
        data.put("totalAmount", invoice.totalAmount().toPlainString());
        data.put("note", "采购发票已审核，可进行过账（过账后生成应付账款，不可撤销）");
        return data;
    }
}
