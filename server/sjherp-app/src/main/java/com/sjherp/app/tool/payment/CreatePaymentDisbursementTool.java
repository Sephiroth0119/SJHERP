package com.sjherp.app.tool.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.payment.PaymentDtos.PaymentDisbursementLineRequest;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementLine;

/**
 * 创建付款单工具（M4-T04c，HIGH——涉及资金与核销，框架强制确认卡片）：登记向某供应商付出一笔款项，
 * 并把付款额分摊核销其若干笔应付账款。建单后为草稿，需审核、过账才真正冲减应付并生成现金侧凭证。
 *
 * <p>与收款单对称。写操作经 {@link PaymentDisbursementAppService#create}（CLAUDE.md 原则 1）；
 * 行参数 lines[{payable_id(整数), allocated_amount(字符串)}]，金额字符串承载（原则 5）；
 * 每行分摊的应付必须属于同一供应商（过账时校验），单据自动 PAYV- 编号；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 finance:settlement（复用核销写权限）。
 */
public class CreatePaymentDisbursementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreatePaymentDisbursementTool.class);

    public static final String NAME = "create_payment_disbursement";

    private final PaymentDisbursementAppService paymentDisbursementAppService;

    public CreatePaymentDisbursementTool(PaymentDisbursementAppService paymentDisbursementAppService) {
        this.paymentDisbursementAppService = Objects.requireNonNull(paymentDisbursementAppService,
                "paymentDisbursementAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建付款单（草稿）：登记向某供应商（supplier_id）付出一笔款项（从资金账户"
                + "payment_account_id 支出），逐行把付款额分摊到该供应商的若干笔应付账款"
                + "（lines：payable_id + allocated_amount）。各行应付必须属于同一供应商、"
                + "分摊额不得超过该应付未核销余额（过账时校验）。建单后为草稿，需审核、过账才真正"
                + "冲减应付并生成现金侧凭证（借应付、贷现金/银行）。"
                + "调用前先在回复正文复述要点（供应商、资金账户、各行应付与分摊金额、合计）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "supplier_id":{"type":"integer","description":"供应商 id（必填，整数）"},\
                "payment_account_id":{"type":"integer","description":"付出的资金账户 id（必填，整数）"},\
                "payment_date":{"type":"string","description":"付款日期（YYYY-MM-DD，可选，省略取当天）"},\
                "remark":{"type":"string","description":"付款说明（可选）"},\
                "lines":{"type":"array","description":"分摊行（每行核销一笔应付）","items":{\
                "type":"object","properties":{\
                "payable_id":{"type":"integer","description":"分摊到的应付账款 id（整数，必填）"},\
                "allocated_amount":{"type":"string","description":"本行分摊（核销）金额（>0，字符串，如 \\"500.00\\"）"}},\
                "required":["payable_id","allocated_amount"],"additionalProperties":false}}},\
                "required":["supplier_id","payment_account_id","lines"],"additionalProperties":false}""";
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
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Long supplierId = longArg(arguments.get("supplier_id"));
        if (supplierId == null) {
            return ToolResult.fail("供应商 id supplier_id 必填（整数）");
        }
        Long paymentAccountId = longArg(arguments.get("payment_account_id"));
        if (paymentAccountId == null) {
            return ToolResult.fail("付出的资金账户 id payment_account_id 必填（整数）");
        }

        LocalDate paymentDate = null;
        String rawDate = ArchiveToolSupport.str(arguments.get("payment_date"));
        if (rawDate != null) {
            try {
                paymentDate = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                return ToolResult.fail("付款日期 payment_date 格式应为 YYYY-MM-DD");
            }
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("付款单至少要有一行（lines 不能为空）");
        }

        List<PaymentDisbursementLineRequest> lines = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("分摊行格式不合法：每行须含 payable_id 与 allocated_amount");
            }
            Map<String, Object> row = (Map<String, Object>) lineMap;

            Long payableId = longArg(row.get("payable_id"));
            if (payableId == null) {
                return ToolResult.fail("分摊行 payable_id 必填（整数）");
            }

            BigDecimal allocatedAmount;
            try {
                allocatedAmount = decimal(row.get("allocated_amount"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage());
            }
            if (allocatedAmount == null) {
                return ToolResult.fail("分摊行 allocated_amount 必填");
            }

            lines.add(new PaymentDisbursementLineRequest(payableId, allocatedAmount));
        }

        String operator = ArchiveToolSupport.operator(context);
        String remark = ArchiveToolSupport.str(arguments.get("remark"));
        try {
            PaymentDisbursement disbursement = paymentDisbursementAppService.create(
                    supplierId, paymentAccountId, paymentDate, remark, lines, operator);
            log.info("Agent 创建付款单（docNo={}, supplierId={}, paymentAccountId={}, lines={}, operator={}, sessionId={}）",
                    disbursement.getDocNo(), supplierId, paymentAccountId, lines.size(), operator,
                    context.sessionId());
            return ToolResult.ok(toData(disbursement));
        } catch (IllegalArgumentException | IllegalStateTransitionException
                 | IllegalStateException e) {
            return ToolResult.fail("创建付款单被拒绝: " + e.getMessage());
        }
    }

    /** 参数值 → Long（null 安全；非整数返回 null 由上层兜底提示） */
    private static Long longArg(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = ArchiveToolSupport.str(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 参数值 → BigDecimal（金额字符串承载；空返回 null，非法抛 IllegalArgumentException） */
    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("分摊金额格式不合法: " + text);
        }
    }

    private static Map<String, Object> toData(PaymentDisbursement disbursement) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", disbursement.getDocNo());
        data.put("status", disbursement.getStatus().name());
        data.put("supplierId", disbursement.getSupplierId());
        data.put("paymentAccountId", disbursement.getPaymentAccountId());
        data.put("paymentDate", disbursement.getPaymentDate().toString());
        data.put("totalAmount", disbursement.totalAmount().toPlainString());
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (PaymentDisbursementLine line : disbursement.getLines()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("lineNo", line.getLineNo());
            r.put("payableId", line.getPayableId());
            r.put("allocatedAmount", line.getAllocatedAmount().toPlainString());
            lineRows.add(r);
        }
        data.put("lines", lineRows);
        data.put("note", "付款单已创建为草稿，需审核后过账才冲减应付并生成现金侧凭证");
        return data;
    }
}
