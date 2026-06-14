package com.sjherp.app.tool.finance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatementLine;
import com.sjherp.app.finance.FinancialStatementService;
import com.sjherp.app.tool.ArchiveToolSupport;

/**
 * 利润表查询工具（M4-T08，NORMAL，只读）：返回指定账期的利润表（小企业会计准则行次），
 * 含营业收入/营业成本/各项费用/营业利润/净利润（本期 + 本年累计两列）。
 *
 * <p>只读经 {@link FinancialStatementService#incomeStatement}（全程只读）。
 * 权限点 finance:report（与 FinancialStatementController 端点同口径）。
 * 金额在 {@link FinancialStatementService} 内已调用 {@code toPlainString}，DTO 字段为字符串。
 */
public class QueryIncomeStatementTool implements Tool {

    public static final String NAME = "query_income_statement";

    private final FinancialStatementService financialStatementService;

    public QueryIncomeStatementTool(FinancialStatementService financialStatementService) {
        this.financialStatementService = Objects.requireNonNull(financialStatementService,
                "financialStatementService 不能为空");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询利润表（期间 + 本年累计）：返回指定账期（yyyyMM）的利润表，"
                + "含营业收入、营业成本、税金及附加、销售/管理/财务费用、营业利润、"
                + "营业外收支、利润总额、所得税、净利润（每行含本期与本年累计两列金额）。"
                + "用户问\"利润表\"\"这个月赚了多少\"\"净利润是多少\"时调用。"
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
            IncomeStatement stmt = financialStatementService.incomeStatement(period);
            return ToolResult.ok(toData(stmt));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("查询利润表失败：" + e.getMessage());
        }
    }

    private static Map<String, Object> toData(IncomeStatement stmt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", stmt.period());
        data.put("netProfitCurrent", stmt.netProfitCurrent());
        data.put("netProfitYtd", stmt.netProfitYtd());

        List<Map<String, Object>> lines = new ArrayList<>();
        for (IncomeStatementLine line : stmt.lines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", line.name());
            row.put("currentPeriod", line.currentPeriod());
            row.put("yearToDate", line.yearToDate());
            lines.add(row);
        }
        data.put("lines", lines);
        return data;
    }
}
