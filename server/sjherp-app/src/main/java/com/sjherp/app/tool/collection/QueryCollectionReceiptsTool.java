package com.sjherp.app.tool.collection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptNotFoundException;
import com.sjherp.domain.collection.CollectionReceiptQuery;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;

/**
 * 查询收款单工具（M4-T04c，NORMAL，登录即可无权限点）：两种用法——
 * (1) 传 doc_no 精确返回单据头与分摊行（客户、资金账户、收款日期、状态、各行应收/分摊金额）；
 * (2) 不传 doc_no 时按客户/资金账户/状态过滤分页返回最多 10 条精简列表。
 *
 * <p>只读经 {@link CollectionReceiptAppService#get} / {@link CollectionReceiptAppService#search}，
 * requiredPermission 返回 null（登录即可）。
 */
public class QueryCollectionReceiptsTool implements Tool {

    public static final String NAME = "query_collection_receipts";

    private final CollectionReceiptAppService collectionReceiptAppService;

    public QueryCollectionReceiptsTool(CollectionReceiptAppService collectionReceiptAppService) {
        this.collectionReceiptAppService = Objects.requireNonNull(collectionReceiptAppService,
                "collectionReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询收款单：传 doc_no（如 RCPT-202606-0001）精确返回单据头（客户、资金账户、收款日期、"
                + "状态、总额）与各分摊行（应收 id、分摊金额）；或不传 doc_no，按客户 id（customer_id）、"
                + "资金账户 id（payment_account_id）、状态（status：DRAFT/APPROVED/COMPLETED 等）过滤，"
                + "分页返回最多 10 条精简列表。用户问某收款单详情或某客户的收款记录时调用。"
                + "登录即可，无需额外权限。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "doc_no":{"type":"string","description":"收款单号（可选；传则忽略其余过滤条件、精确返回该单）"},\
                "customer_id":{"type":"integer","description":"客户 id 过滤（可选，整数）"},\
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
                return ToolResult.ok(toDetail(collectionReceiptAppService.get(docNo)));
            } catch (CollectionReceiptNotFoundException e) {
                return ToolResult.fail("收款单不存在: " + docNo);
            }
        }

        Long customerId = longArg(arguments.get("customer_id"));
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

        CollectionReceiptQuery query = new CollectionReceiptQuery(customerId, paymentAccountId, status,
                1, ArchiveToolSupport.MAX_ITEMS);
        PageResult<CollectionReceipt> result = collectionReceiptAppService.search(query);
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

    private static Map<String, Object> toDetail(CollectionReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("customerId", receipt.getCustomerId());
        data.put("paymentAccountId", receipt.getPaymentAccountId());
        data.put("receiptDate", receipt.getReceiptDate().toString());
        data.put("remark", receipt.getRemark());
        data.put("totalAmount", receipt.totalAmount().toPlainString());
        List<Map<String, Object>> lineRows = new ArrayList<>();
        for (CollectionReceiptLine line : receipt.getLines()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("lineNo", line.getLineNo());
            r.put("receivableId", line.getReceivableId());
            r.put("allocatedAmount", line.getAllocatedAmount().toPlainString());
            lineRows.add(r);
        }
        data.put("lines", lineRows);
        return data;
    }

    private static Map<String, Object> toList(PageResult<CollectionReceipt> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.total());
        List<Map<String, Object>> items = new ArrayList<>();
        for (CollectionReceipt receipt : result.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docNo", receipt.getDocNo());
            row.put("status", receipt.getStatus().name());
            row.put("customerId", receipt.getCustomerId());
            row.put("paymentAccountId", receipt.getPaymentAccountId());
            row.put("receiptDate", receipt.getReceiptDate().toString());
            row.put("totalAmount", receipt.totalAmount().toPlainString());
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
