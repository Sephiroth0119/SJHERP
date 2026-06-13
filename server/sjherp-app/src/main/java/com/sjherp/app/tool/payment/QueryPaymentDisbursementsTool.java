package com.sjherp.app.tool.payment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementLine;
import com.sjherp.domain.payment.PaymentDisbursementNotFoundException;
import com.sjherp.domain.payment.PaymentDisbursementQuery;

/**
 * 查询付款单工具（M4-T04c，NORMAL，登录即可无权限点）：两种用法——
 * (1) 传 doc_no 精确返回单据头与分摊行（供应商、资金账户、付款日期、状态、各行应付/分摊金额）；
 * (2) 不传 doc_no 时按供应商/资金账户/状态过滤分页返回最多 10 条精简列表。
 *
 * <p>与收款单对称。只读经 {@link PaymentDisbursementAppService#get} /
 * {@link PaymentDisbursementAppService#search}，requiredPermission 返回 null（登录即可）。
 */
public class QueryPaymentDisbursementsTool implements Tool {

    public static final String NAME = "query_payment_disbursements";

    private final PaymentDisbursementAppService paymentDisbursementAppService;

    public QueryPaymentDisbursementsTool(PaymentDisbursementAppService paymentDisbursementAppService) {
        this.paymentDisbursementAppService = Objects.requireNonNull(paymentDisbursementAppService,
                "paymentDisbursementAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询付款单：传 doc_no（如 PAYV-202606-0001）精确返回单据头（供应商、资金账户、付款日期、"
                + "状态、总额）与各分摊行（应付 id、分摊金额）；或不传 doc_no，按供应商 id（supplier_id）、"
                + "资金账户 id（payment_account_id）、状态（status：DRAFT/APPROVED/COMPLETED 等）过滤，"
                + "分页返回最多 10 条精简列表。用户问某付款单详情或某供应商的付款记录时调用。"
                + "登录即可，无需额外权限。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"付款单号（可选；传则忽略其余过滤条件、精确返回该单）"},\
                "supplier_id":{"type":"integer","description":"供应商 id 过滤（可选，整数）"},\
                "payment_account_id":{"type":"integer","description":"资金账户 id 过滤（可选，整数）"},\
                "status":{"type":"string","enum":["DRAFT","APPROVED","EXECUTING","COMPLETED","REVERSED","CANCELLED"],\
                "description":"单据状态过滤（可选）"}},\
                "additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return null;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String docNo = ArchiveToolSupport.str(arguments.get("doc_no"));
        if (docNo != null) {
            try {
                return ToolResult.ok(toDetail(paymentDisbursementAppService.get(docNo)));
            } catch (PaymentDisbursementNotFoundException e) {
                return ToolResult.fail("付款单不存在: " + docNo);
            }
        }

        Long supplierId = longArg(arguments.get("supplier_id"));
        Long paymentAccountId = longArg(arguments.get("payment_account_id"));
        DocumentStatus status = null;
        String statusText = ArchiveToolSupport.str(arguments.get("status"));
        if (statusText != null) {
            try {
                status = DocumentStatus.valueOf(statusText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail("单据状态不合法: " + statusText);
            }
        }

        PaymentDisbursementQuery query = new PaymentDisbursementQuery(supplierId, paymentAccountId,
                status, 1, ArchiveToolSupport.MAX_ITEMS);
        PageResult<PaymentDisbursement> result = paymentDisbursementAppService.search(query);
        return ToolResult.ok(toList(result));
    }

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

    private static Map<String, Object> toDetail(PaymentDisbursement disbursement) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", disbursement.getDocNo());
        data.put("status", disbursement.getStatus().name());
        data.put("supplierId", disbursement.getSupplierId());
        data.put("paymentAccountId", disbursement.getPaymentAccountId());
        data.put("paymentDate", disbursement.getPaymentDate().toString());
        data.put("remark", disbursement.getRemark());
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
        return data;
    }

    private static Map<String, Object> toList(PageResult<PaymentDisbursement> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.total());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PaymentDisbursement disbursement : result.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docNo", disbursement.getDocNo());
            row.put("status", disbursement.getStatus().name());
            row.put("supplierId", disbursement.getSupplierId());
            row.put("paymentAccountId", disbursement.getPaymentAccountId());
            row.put("paymentDate", disbursement.getPaymentDate().toString());
            row.put("totalAmount", disbursement.totalAmount().toPlainString());
            items.add(row);
        }
        data.put("items", items);
        if (result.total() > items.size()) {
            data.put("note", "共 " + result.total() + " 条，仅返回前 " + items.size()
                    + " 条，请引导用户补充过滤条件缩小范围");
        }
        return data;
    }
}
