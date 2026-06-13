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
import com.sjherp.app.sales.SalesInvoiceAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.sales.SalesInvoice;
import com.sjherp.domain.sales.SalesInvoiceNotFoundException;

/**
 * 审核销售发票工具（M3-T11，HIGH——状态流转，框架强制确认卡片）：将销售发票从草稿（DRAFT）
 * 推进至已审核（APPROVED），审核后方可过账生成应收账款。
 *
 * <p>写操作经 {@link SalesInvoiceAppService#approve}（CLAUDE.md 原则 1）；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 sales:invoice（ADMIN/BOSS/ACCOUNTANT）。
 */
public class ApproveSalesInvoiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ApproveSalesInvoiceTool.class);

    public static final String NAME = "approve_sales_invoice";

    private final SalesInvoiceAppService salesInvoiceAppService;

    public ApproveSalesInvoiceTool(SalesInvoiceAppService salesInvoiceAppService) {
        this.salesInvoiceAppService = Objects.requireNonNull(salesInvoiceAppService,
                "salesInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "审核销售发票（DRAFT → APPROVED）：确认发票数据无误、允许后续过账生成应收账款。"
                + "审核不产生应收，过账才真正生成应收。"
                + "调用前先在回复正文复述要点（将审核销售发票 <doc_no>）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售发票号（如 SINV-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "sales:invoice";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("销售发票号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            SalesInvoice invoice = salesInvoiceAppService.approve(docNo, operator);
            log.info("Agent 审核销售发票（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(invoice));
        } catch (SalesInvoiceNotFoundException e) {
            return ToolResult.fail("销售发票不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("审核销售发票被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("审核被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesInvoice invoice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", invoice.getDocNo());
        data.put("status", invoice.getStatus().name());
        data.put("salesDeliveryNo", invoice.getSalesDeliveryNo());
        data.put("totalAmount", invoice.totalAmount().toPlainString());
        data.put("note", "销售发票已审核，可进行过账（过账后生成应收账款，不可撤销）");
        return data;
    }
}
