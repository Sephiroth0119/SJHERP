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
 * 冲销销售发票工具（M4-T07b，HIGH——红字冲销不可逆，框架强制确认卡片）：将已过账销售发票
 * （COMPLETED → REVERSED）红冲。同事务内应收<b>整笔冲回</b>（须无核销）、回退出库行累计已开票量、
 * 红冲发票自动凭证（借贷对调，原凭证转已冲销）。
 *
 * <p><b>带核销的发票须先冲对应收款单</b>（应收 canBeReversed 前置硬拒，引导先冲收款单 T07c——
 * 保数据模型不破碎，不做带核销的递归级联）。财务记录只可冲销不可物理删除（CLAUDE.md 原则 2）；
 * 关账期内不可冲销（凭证红冲账期 OPEN 校验拦截）。写操作经 {@link SalesInvoiceAppService#reverse}
 * （CLAUDE.md 原则 1）；审计操作人记 agent:&lt;userId&gt;。权限点 sales:invoice（ADMIN/BOSS/ACCOUNTANT）。
 */
public class ReverseSalesInvoiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReverseSalesInvoiceTool.class);

    public static final String NAME = "reverse_sales_invoice";

    private final SalesInvoiceAppService salesInvoiceAppService;

    public ReverseSalesInvoiceTool(SalesInvoiceAppService salesInvoiceAppService) {
        this.salesInvoiceAppService = Objects.requireNonNull(salesInvoiceAppService,
                "salesInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销销售发票（红字发票，COMPLETED → REVERSED）：将已过账发票红冲，"
                + "应收账款整笔冲回、回退出库行已开票量、红冲发票记账凭证（借贷对调）。"
                + "原单转为已冲销、不可再用（财务记录只可冲销不可删除）。"
                + "若该发票已被收款核销，须先冲销对应收款单后再冲发票（系统会拒绝并提示）。"
                + "关账期内不可冲销（须先重开账期）。"
                + "调用前先在回复正文复述要点（将冲销销售发票 <doc_no>，应收冲回、原单作废、不可逆）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"销售发票号（如 SINV-202606-0001，须已过账）"}},\
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
            SalesInvoice invoice = salesInvoiceAppService.reverse(docNo, operator);
            log.info("Agent 冲销销售发票（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(invoice));
        } catch (SalesInvoiceNotFoundException e) {
            return ToolResult.fail("销售发票不存在: " + docNo);
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("冲销销售发票被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(SalesInvoice invoice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", invoice.getDocNo());
        data.put("status", invoice.getStatus().name());
        data.put("salesDeliveryNo", invoice.getSalesDeliveryNo());
        data.put("customerId", invoice.getCustomerId());
        data.put("reversedById", invoice.getReversedById());
        data.put("note", "销售发票已冲销，应收账款已整笔冲回，发票凭证已红冲，出库行已开票量已回退");
        return data;
    }
}
