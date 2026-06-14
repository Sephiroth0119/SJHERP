package com.sjherp.app.tool.finance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.settlement.SettlementReadAppService;
import com.sjherp.domain.settlement.SettlementRecord;

/**
 * 应收核销历史查询工具（M4-T08，NORMAL，只读）：按应收子账 ID 查询该笔应收的全部核销流水记录，
 * 包含核销金额、核销日、关联收款单号等字段，用于追溯应收的收款进度与核销详情。
 *
 * <p>只读经 {@link SettlementReadAppService#findReceivableSettlements}。
 * 权限点 finance:settlement（与 /api/settlements 端点同口径）。
 * 金额一律 {@link java.math.BigDecimal#toPlainString}，日期 ISO 字符串，时间 UTC ISO 字符串。
 */
public class QueryReceivableSettlementsTool implements Tool {

    public static final String NAME = "query_receivable_settlements";

    private final SettlementReadAppService settlementReadAppService;

    public QueryReceivableSettlementsTool(SettlementReadAppService settlementReadAppService) {
        this.settlementReadAppService = Objects.requireNonNull(settlementReadAppService,
                "settlementReadAppService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询某笔应收的核销历史（按发生先后）：传入应收子账数据库 ID（receivableId），"
                + "返回该笔应收账款的全部核销流水，含本次核销金额、核销业务日、关联收款单号（paymentDocNo）"
                + "及来源发票单号（targetSourceDocNo）。"
                + "用户问\"这张发票收到多少款了\"\"应收核销记录\"\"收款进度\"时调用。"
                + "receivableId 必填（整数，应收子账数据库 ID）。"
                + "需要 finance:settlement 权限。金额返回字符串格式（非 JSON 数字）。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "receivableId":{"type":"integer",\
                "description":"应收子账数据库 ID（accounts_receivable.id）"}},\
                "required":["receivableId"],"additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return "finance:settlement";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        Object idRaw = arguments.get("receivableId");
        if (idRaw == null) {
            return ToolResult.fail("receivableId 必填（应收子账数据库 ID）");
        }
        long receivableId;
        if (idRaw instanceof Number n) {
            receivableId = n.longValue();
        } else {
            return ToolResult.fail("receivableId 须为整数（应收子账数据库 ID）");
        }
        if (receivableId <= 0) {
            return ToolResult.fail("receivableId 须为正整数");
        }

        List<SettlementRecord> records = settlementReadAppService.findReceivableSettlements(receivableId);
        return ToolResult.ok(toData(receivableId, records));
    }

    static Map<String, Object> toData(long targetId, List<SettlementRecord> records) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetId", targetId);
        data.put("count", records.size());

        List<Map<String, Object>> items = new ArrayList<>();
        for (SettlementRecord r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("type", r.getType().name());
            row.put("targetSourceDocNo", r.getTargetSourceDocNo());
            row.put("amount", r.getAmount().toPlainString());
            row.put("settlementDate", r.getSettlementDate().toString());
            row.put("paymentDocNo", r.getPaymentDocNo());
            row.put("createdBy", r.getCreatedBy());
            row.put("createdAt", r.getCreatedAt().toString());
            items.add(row);
        }
        data.put("records", items);
        return data;
    }
}
