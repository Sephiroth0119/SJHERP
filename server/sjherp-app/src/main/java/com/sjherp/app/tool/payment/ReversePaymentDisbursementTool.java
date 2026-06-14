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
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementNotFoundException;

/**
 * 冲销付款单工具（M4-T07c，HIGH——红字单、不可逆，框架强制确认卡片）：与冲销收款单对称。对已过账
 * （COMPLETED）的付款单红冲——按付款单号反查已发生的应付核销记录逐条反向冲回（应付已核销额回退、状态
 * 回到部分核销/未核销），并红冲现金侧凭证（借贷对调），原单转「已冲销」（REVERSED）。
 *
 * <p>写操作经 {@link PaymentDisbursementAppService#reverse}（CLAUDE.md 原则 1：唯一写入口，绝不绕过领域模型，
 * 反向核销经核销引擎、凭证红冲经凭证唯一写入口），全程同事务原子。已冲销/未过账单不可冲销；同一单据只能
 * 冲销一次（重复被拒）；现金侧凭证账期若已关账须先重开（高敏）。<b>冲付款单会解锁对应已核销采购发票的红冲</b>
 * （应付核销额回退至 0 后采购发票方可冲销）。权限点 finance:settlement。
 */
public class ReversePaymentDisbursementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReversePaymentDisbursementTool.class);

    public static final String NAME = "reverse_payment_disbursement";

    private final PaymentDisbursementAppService paymentDisbursementAppService;

    public ReversePaymentDisbursementTool(PaymentDisbursementAppService paymentDisbursementAppService) {
        this.paymentDisbursementAppService = Objects.requireNonNull(paymentDisbursementAppService,
                "paymentDisbursementAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "冲销付款单（红字单，不可逆，高风险）：对指定的已过账付款单 <doc_no> 做整单红冲——"
                + "按付款单号反查已发生的应付核销记录逐条反向冲回（应付已核销额回退、状态回到部分核销/未核销），"
                + "并红冲现金侧凭证（借贷对调），原付款单随之转为「已冲销」状态。"
                + "已冲销/未过账付款单不可冲销；同一单只能冲销一次（重复被拒）；现金侧凭证所在账期若已关账须先重开（高敏）。"
                + "冲付款单会解锁对应已核销采购发票的红冲。操作不可逆。"
                + "调用前先在回复正文复述要点（将对已过账付款单 <doc_no> 反向核销应付、红冲现金侧凭证、原单转已冲销、"
                + "不可逆）；系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"被冲销的付款单号（如 PAYV-202606-0001，须为已过账单据）"}},\
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
            PaymentDisbursement disbursement = paymentDisbursementAppService.reverse(docNo, operator);
            log.info("Agent 冲销付款单（docNo={}, operator={}, sessionId={}）",
                    docNo, operator, context.sessionId());
            return ToolResult.ok(toData(disbursement));
        } catch (PaymentDisbursementNotFoundException e) {
            return ToolResult.fail("付款单不存在: " + docNo);
        } catch (PeriodClosedException e) {
            return ToolResult.fail("冲销被拒绝（现金侧凭证账期已关账，须先重开）: " + e.getMessage());
        } catch (IllegalStateTransitionException | IllegalStateException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("冲销被拒绝: " + e.getMessage());
        }
    }

    private static Map<String, Object> toData(PaymentDisbursement disbursement) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", disbursement.getDocNo());
        data.put("status", disbursement.getStatus().name());
        data.put("totalAmount", disbursement.totalAmount().toPlainString());
        data.put("note", "付款单已冲销：应付核销已反向冲回、现金侧凭证已红冲（不可逆，已解锁对应采购发票红冲）");
        return data;
    }
}
