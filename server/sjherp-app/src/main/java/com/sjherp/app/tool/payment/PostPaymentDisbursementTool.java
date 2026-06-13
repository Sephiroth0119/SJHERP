package com.sjherp.app.tool.payment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.fund.PaymentAccountNotFoundException;
import com.sjherp.domain.payable.PayableNotFoundException;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementNotFoundException;

/**
 * 过账付款单工具（M4-T04c，HIGH——涉及资金过账与核销，不可撤销，框架强制确认卡片）：将已审核付款单
 * （APPROVED → COMPLETED）过账。<b>同一事务内</b>逐行冲减对应应付子账（核销，超额硬拒）、并生成
 * 现金侧凭证（借应付账款、贷现金/银行），任一失败整单回滚（资金/核销/凭证/单据状态不半生效）。
 *
 * <p>与收款单对称。过账后不可撤销（纠错走统一冲销 M4-T07）。写操作经
 * {@link PaymentDisbursementAppService#post}（CLAUDE.md 原则 1）；审计操作人记 agent:&lt;userId&gt;。
 * 权限点 finance:settlement（复用核销写权限）。
 */
public class PostPaymentDisbursementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PostPaymentDisbursementTool.class);

    public static final String NAME = "post_payment_disbursement";

    private final PaymentDisbursementAppService paymentDisbursementAppService;

    public PostPaymentDisbursementTool(PaymentDisbursementAppService paymentDisbursementAppService) {
        this.paymentDisbursementAppService = Objects.requireNonNull(paymentDisbursementAppService,
                "paymentDisbursementAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "过账付款单（APPROVED → COMPLETED）：将已审核付款单过账。同一事务内逐行冲减对应供应商的"
                + "应付账款（核销，分摊额超过未核销余额会被拒绝并整单回滚），并生成现金侧凭证"
                + "（借应付账款、贷付出的资金账户对应现金/银行科目）。过账后不可撤销（纠错走冲销）。"
                + "调用前先在回复正文复述要点（将过账付款单 <doc_no>、冲减的应付明细与金额、"
                + "付出的资金账户、生成现金侧凭证、不可撤销）；系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"付款单号（如 PAYV-202606-0001）"}},\
                "required":["doc_no"],"additionalProperties":false}""";
    }

    @Override
    public ToolRiskLevel riskLevel() {
        return ToolRiskLevel.HIGH;
    }

    @Override
    public String requiredPermission() {
        return "finance:settlement";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo == null) {
            return ToolResult.fail("付款单号 doc_no 必填");
        }
        String operator = ArchiveToolSupport.operator(context);
        try {
            PaymentDisbursement disbursement = paymentDisbursementAppService.post(docNo, operator);
            log.info("Agent 过账付款单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(disbursement));
        } catch (PaymentDisbursementNotFoundException e) {
            return ToolResult.fail("付款单不存在: " + docNo);
        } catch (PaymentAccountNotFoundException e) {
            return ToolResult.fail("过账被拒绝（资金账户不存在）: " + e.getMessage());
        } catch (PayableNotFoundException e) {
            return ToolResult.fail("过账被拒绝（应付账款不存在）: " + e.getMessage());
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            // 含 PeriodClosedException（账期已关）与单据状态机非法流转
            return ToolResult.fail("过账付款单被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // 含 OverSettlementException（超额核销）、跨供应商核销、资金账户停用等
            return ToolResult.fail("过账被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PaymentDisbursement disbursement) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", disbursement.getDocNo());
        data.put("status", disbursement.getStatus().name());
        data.put("supplierId", disbursement.getSupplierId());
        data.put("paymentAccountId", disbursement.getPaymentAccountId());
        data.put("totalAmount", disbursement.totalAmount().toPlainString());
        data.put("note", "付款单已过账，应付已冲减、现金侧凭证已生成（不可撤销，纠错走冲销）");
        return data;
    }
}
