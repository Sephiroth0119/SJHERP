package com.sjherp.app.tool.collection;

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
import com.sjherp.app.collection.CollectionDtos.CollectionReceiptLineRequest;
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.app.tool.ArchiveToolSupport;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.common.IllegalStateTransitionException;

/**
 * 创建收款单工具（M4-T04c，HIGH——涉及资金与核销，框架强制确认卡片）：登记从某客户收到一笔款项，
 * 并把收款额分摊核销其若干笔应收账款。建单后为草稿，需审核、过账才真正冲减应收并生成现金侧凭证。
 *
 * <p>写操作经 {@link CollectionReceiptAppService#create}（CLAUDE.md 原则 1）；
 * 行参数 lines[{receivable_id(整数), allocated_amount(字符串)}]，金额字符串承载（原则 5）；
 * 每行分摊的应收必须属于同一客户（过账时校验），单据自动 RCPT- 编号；
 * 审计操作人记 agent:&lt;userId&gt;。权限点 finance:settlement（复用核销写权限）。
 */
public class CreateCollectionReceiptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CreateCollectionReceiptTool.class);

    public static final String NAME = "create_collection_receipt";

    private final CollectionReceiptAppService collectionReceiptAppService;

    public CreateCollectionReceiptTool(CollectionReceiptAppService collectionReceiptAppService) {
        this.collectionReceiptAppService = Objects.requireNonNull(collectionReceiptAppService,
                "collectionReceiptAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "创建收款单（草稿）：登记从某客户（customer_id）收到一笔款项并存入某资金账户"
                + "（payment_account_id），逐行把收款额分摊到该客户的若干笔应收账款"
                + "（lines：receivable_id + allocated_amount）。各行应收必须属于同一客户、"
                + "分摊额不得超过该应收未核销余额（过账时校验）。建单后为草稿，需审核、过账才真正"
                + "冲减应收并生成现金侧凭证（借现金/银行、贷应收）。"
                + "调用前先在回复正文复述要点（客户、资金账户、各行应收与分摊金额、合计）；"
                + "系统会自动请求用户确认后才执行。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "customer_id":{"type":"integer","description":"客户 id（必填，整数）"},\
                "payment_account_id":{"type":"integer","description":"收入的资金账户 id（必填，整数）"},\
                "receipt_date":{"type":"string","description":"收款日期（YYYY-MM-DD，可选，省略取当天）"},\
                "remark":{"type":"string","description":"收款说明（可选）"},\
                "lines":{"type":"array","description":"分摊行（每行核销一笔应收）","items":{\
                "type":"object","properties":{\
                "receivable_id":{"type":"integer","description":"分摊到的应收账款 id（整数，必填）"},\
                "allocated_amount":{"type":"string","description":"本行分摊（核销）金额（>0，字符串，如 \\"500.00\\"）"}},\
                "required":["receivable_id","allocated_amount"],"additionalProperties":false}}},\
                "required":["customer_id","payment_account_id","lines"],"additionalProperties":false}""";
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
        Long customerId = longArg(arguments.get("customer_id"));
        if (customerId == null) {
            return ToolResult.fail("客户 id customer_id 必填（整数）");
        }
        Long paymentAccountId = longArg(arguments.get("payment_account_id"));
        if (paymentAccountId == null) {
            return ToolResult.fail("收入的资金账户 id payment_account_id 必填（整数）");
        }

        LocalDate receiptDate = null;
        String rawDate = ArchiveToolSupport.str(arguments.get("receipt_date"));
        if (rawDate != null) {
            try {
                receiptDate = LocalDate.parse(rawDate);
            } catch (DateTimeParseException e) {
                return ToolResult.fail("收款日期 receipt_date 格式应为 YYYY-MM-DD");
            }
        }

        Object rawLines = arguments.get("lines");
        if (!(rawLines instanceof List<?> lineList) || lineList.isEmpty()) {
            return ToolResult.fail("收款单至少要有一行（lines 不能为空）");
        }

        List<CollectionReceiptLineRequest> lines = new ArrayList<>(lineList.size());
        for (Object item : lineList) {
            if (!(item instanceof Map<?, ?> lineMap)) {
                return ToolResult.fail("分摊行格式不合法：每行须含 receivable_id 与 allocated_amount");
            }
            Map<String, Object> row = (Map<String, Object>) lineMap;

            Long receivableId = longArg(row.get("receivable_id"));
            if (receivableId == null) {
                return ToolResult.fail("分摊行 receivable_id 必填（整数）");
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

            lines.add(new CollectionReceiptLineRequest(receivableId, allocatedAmount));
        }

        String operator = ArchiveToolSupport.operator(context);
        String remark = ArchiveToolSupport.str(arguments.get("remark"));
        try {
            CollectionReceipt receipt = collectionReceiptAppService.create(
                    customerId, paymentAccountId, receiptDate, remark, lines, operator);
            log.info("Agent 创建收款单（docNo={}, customerId={}, paymentAccountId={}, lines={}, operator={}, sessionId={}）",
                    receipt.getDocNo(), customerId, paymentAccountId, lines.size(), operator,
                    context.sessionId());
            return ToolResult.ok(toData(receipt));
        } catch (IllegalArgumentException | IllegalStateTransitionException
                 | IllegalStateException e) {
            return ToolResult.fail("创建收款单被拒绝: " + e.getMessage());
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

    private static Map<String, Object> toData(CollectionReceipt receipt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docNo", receipt.getDocNo());
        data.put("status", receipt.getStatus().name());
        data.put("customerId", receipt.getCustomerId());
        data.put("paymentAccountId", receipt.getPaymentAccountId());
        data.put("receiptDate", receipt.getReceiptDate().toString());
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
        data.put("note", "收款单已创建为草稿，需审核后过账才冲减应收并生成现金侧凭证");
        return data;
    }
}
