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
 * 过账采购发票工具（M3-T11，HIGH——生成应付账款，不可撤销，框架强制确认卡片）：将已审核采购发票
 * （APPROVED → COMPLETED）过账，生成应付账款（欠供应商的钱），到期日由供应商结算方式推算。
 *
 * <p>过账后应付生成，不可撤销（纠错走红字发票冲销 M4-T07）。
 * 写操作经 {@link PurchaseInvoiceAppService#post}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 purchase:invoice（ADMIN/BOSS/ACCOUNTANT）。
 */
public class PostPurchaseInvoiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostPurchaseInvoiceTool.class);

    public static final String NAME = "post_purchase_invoice";

    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public PostPurchaseInvoiceTool(PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.purchaseInvoiceAppService = Objects.requireNonNull(purchaseInvoiceAppService,
                "purchaseInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "过账采购发票（APPROVED → COMPLETED）：将已审核采购发票过账，生成应付账款"
                + "（欠供应商的钱），到期日由供应商结算方式自动推算。过账后不可撤销（纠错走红字冲销）。"
                + "调用前先在回复正文复述要点（将过账采购发票 <doc_no>，过账后形成应付、不可撤销）；"
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
            PurchaseInvoice invoice = purchaseInvoiceAppService.post(docNo, operator);
            log.info("Agent 过账采购发票（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(invoice));
        } catch (PurchaseInvoiceNotFoundException e) {
            return ToolResult.fail("采购发票不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("过账采购发票被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("过账被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseInvoice invoice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", invoice.getDocNo());
        data.put("status", invoice.getStatus().name());
        data.put("purchaseReceiptNo", invoice.getPurchaseReceiptNo());
        data.put("supplierId", invoice.getSupplierId());
        data.put("totalAmount", invoice.totalAmount().toPlainString());
        data.put("note", "采购发票已过账，应付账款已生成");
        return data;
    }
}
