package com.sjherp.app.tool.finance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheetLine;
import com.sjherp.app.finance.FinancialStatementService;
import com.sjherp.app.tool.ArchiveToolSupport;

/**
 * 资产负债表查询工具（M4-T08，NORMAL，只读）：返回指定账期末时点的资产负债表（小企业会计准则行次），
 * 含资产/负债/权益三组报表行、各组合计、以及平衡标志（balanced = 资产合计 == 负债+权益合计）。
 *
 * <p>只读经 {@link FinancialStatementService#balanceSheet}（全程只读）。
 * 权限点 finance:report（与 FinancialStatementController 端点同口径）。
 * 金额在 {@link FinancialStatementService} 内已调用 {@code toPlainString}，DTO 字段为字符串。
 */
public class QueryBalanceSheetTool implements Tool {

    public static final String NAME = "query_balance_sheet";

    private final FinancialStatementService financialStatementService;

    public QueryBalanceSheetTool(FinancialStatementService financialStatementService) {
        this.financialStatementService = Objects.requireNonNull(financialStatementService,
                "financialStatementService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询资产负债表（时点）：返回指定账期（yyyyMM）末的资产负债表，含货币资金/应收账款/存货等"
                + "资产行、应付账款/短期借款等负债行、实收资本/未分配利润等权益行，以及三组合计与平衡标志。"
                + "用户问\"资产负债表\"\"资产总额是多少\"\"未分配利润\"时调用。"
                + "period 必填（格式 yyyyMM，如 202606）。"
                + "需要 finance:report 权限。金额返回字符串格式（非 JSON 数字）。";
    }

    @Override
    public String parameterSchema() {
        return """
                {"type":"object","properties":{\
                "period":{"type":"string","pattern":"^[0-9]{6}$",\
                "description":"账期键 yyyyMM（如 202606）"}},\
                "required":["period"],"additionalProperties":false}""";
    }

    @Override
    public String requiredPermission() {
        return "finance:report";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String period = ArchiveToolSupport.str(arguments.get("period"));
        if (period == null || period.isBlank()) {
            return ToolResult.fail("period 必填（格式 yyyyMM，如 202606）");
        }
        if (!period.matches("^[0-9]{6}$")) {
            return ToolResult.fail("period 格式错误，须为 6 位数字 yyyyMM，如 202606");
        }

        try {
            BalanceSheet sheet = financialStatementService.balanceSheet(period);
            return ToolResult.ok(toData(sheet));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("查询资产负债表失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> toData(BalanceSheet sheet) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", sheet.period());
        data.put("assetLines", toLineList(sheet.assetLines()));
        data.put("totalAssets", sheet.totalAssets());
        data.put("liabilityLines", toLineList(sheet.liabilityLines()));
        data.put("totalLiabilities", sheet.totalLiabilities());
        data.put("equityLines", toLineList(sheet.equityLines()));
        data.put("totalEquity", sheet.totalEquity());
        data.put("balanced", sheet.balanced());
        return data;
    }

    private static List<Map<String, Object>> toLineList(List<BalanceSheetLine> lines) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BalanceSheetLine line : lines) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", line.name());
            row.put("amount", line.amount());
            result.add(row);
        }
        return result;
    }
}
