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
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceNotFoundException;

/**
 * 冲销采购发票工具（M4-T07b，HIGH——红字发票、不可逆，框架强制确认卡片）：对已过账（COMPLETED）的
 * 采购发票红冲——回退收货行已开票量、冲回应付台账（须未核销）、红冲发票自动凭证（借贷对调），
 * 原发票转「已冲销」（REVERSED）。
 *
 * <p>写操作经 {@link PurchaseInvoiceAppService#reverse}（CLAUDE.md 原则 1：唯一写入口，绝不绕过领域模型），
 * 全程同事务原子。已冲销/未过账发票不可冲销；<b>已（部分）核销的发票须先冲销对应付款单后再冲发票</b>
 * （否则拒绝并清晰报错）；发票自动凭证账期若已关账须先重开（高敏）。权限点 purchase:invoice。
 */
public class ReversePurchaseInvoiceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReversePurchaseInvoiceTool.class);

    public static final String NAME = "reverse_purchase_invoice";

    private final PurchaseInvoiceAppService purchaseInvoiceAppService;

    public ReversePurchaseInvoiceTool(PurchaseInvoiceAppService purchaseInvoiceAppService) {
        this.purchaseInvoiceAppService = Objects.requireNonNull(purchaseInvoiceAppService,
                "purchaseInvoiceAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销采购发票（红字发票，不可逆，高风险）：对指定的已过账采购发票 <doc_no> 做整单红冲——"
                + "回退收货行已开票量（使该收货单可重新开票）、冲回对应应付台账（须未核销）、"
                + "红冲发票自动凭证（借贷对调），原发票随之转为「已冲销」状态。已冲销/未过账发票不可冲销；"
                + "若对应应付已（部分）核销，须先冲销对应的付款单后再冲发票（否则被拒）；"
                + "发票自动凭证所在账期若已关账须先重开（高敏）。操作不可逆。"
                + "调用前先在回复正文复述要点（将对已过账发票 <doc_no> 回退开票量、冲回应付、红冲凭证、原单转已冲销、"
                + "不可逆）；系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"被冲销的采购发票号（如 PINV-202606-0001，须为已过账单据）"}},\
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
            PurchaseInvoice invoice = purchaseInvoiceAppService.reverse(docNo, operator);
            log.info("Agent 冲销采购发票（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(invoice));
        } catch (PurchaseInvoiceNotFoundException e) {
            return ToolResult.fail("采购发票不存在: " + docNo);
        } catch (PeriodClosedException e) {
            return ToolResult.fail("冲销被拒绝（发票凭证账期已关账，须先重开）: " + e.getMessage());
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            // 非 COMPLETED / 已冲销 / 应付已核销（须先冲付款单）等状态约束类拒绝
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PurchaseInvoice invoice) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", invoice.getDocNo());
        data.put("status", invoice.getStatus().name());
        data.put("purchaseReceiptNo", invoice.getPurchaseReceiptNo());
        data.put("totalAmount", invoice.totalAmount().toPlainString());
        data.put("note", "采购发票已冲销：收货行开票量已回退、应付已冲回、发票凭证已红冲（不可逆）");
        return data;
    }
}
