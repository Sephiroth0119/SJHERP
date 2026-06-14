package com.sjherp.app.tool.finance;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.settlement.SettlementReadAppService;
import com.sjherp.domain.settlement.SettlementRecord;

/**
 * 应付核销历史查询工具（M4-T08，NORMAL，只读）：按应付子账 ID 查询该笔应付的全部核销流水记录，
 * 包含核销金额、核销日、关联付款单号等字段，用于追溯应付的付款进度与核销详情。
 *
 * <p>只读经 {@link SettlementReadAppService#findPayableSettlements}。
 * 权限点 finance:settlement（与 /api/settlements 端点同口径）。
 * 金额一律 {@link java.math.BigDecimal#toPlainString}，日期 ISO 字符串，时间 UTC ISO 字符串。
 */
public class QueryPayableSettlementsTool implements Tool {

    public static final String NAME = "query_payable_settlements";

    private final SettlementReadAppService settlementReadAppService;

    public QueryPayableSettlementsTool(SettlementReadAppService settlementReadAppService) {
        this.settlementReadAppService = Objects.requireNonNull(settlementReadAppService,
                "settlementReadAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询某笔应付的核销历史（按发生先后）：传入应付子账数据库 ID（payableId），"
                + "返回该笔应付账款的全部核销流水，含本次核销金额、核销业务日、关联付款单号（paymentDocNo）"
                + "及来源发票单号（targetSourceDocNo）。"
                + "用户问\"这张采购发票付了多少\"\"应付核销记录\"\"付款进度\"时调用。"
                + "payableId 必填（整数，应付子账数据库 ID）。"
                + "需要 finance:settlement 权限。金额返回字符串格式（非 JSON 数字）。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "payableId":{"type":"integer",\
                "description":"应付子账数据库 ID（accounts_payable.id）"}},\
                "required":["payableId"],"additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return "finance:settlement";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Object idRaw = arguments.get("payableId");
        if (idRaw == null) {
            return ToolResult.fail("payableId 必填（应付子账数据库 ID）");
        }
        long payableId;
        if (idRaw instanceof Number n) {
            payableId = n.longValue();
        } else {
            return ToolResult.fail("payableId 须为整数（应付子账数据库 ID）");
        }
        if (payableId <= 0) {
            return ToolResult.fail("payableId 须为正整数");
        }

        List<SettlementRecord> records = settlementReadAppService.findPayableSettlements(payableId);
        return ToolResult.ok(QueryReceivableSettlementsTool.toData(payableId, records));
    }
}
