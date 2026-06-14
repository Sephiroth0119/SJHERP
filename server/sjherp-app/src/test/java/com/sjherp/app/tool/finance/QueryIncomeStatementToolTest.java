package com.sjherp.app.tool.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatement;
import com.sjherp.app.finance.FinancialStatementDtos.IncomeStatementLine;
import com.sjherp.app.finance.FinancialStatementService;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * query_income_statement 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:report/
 * parameterSchema（period 必填、^[0-9]{6}$ pattern）/execute 调 FinancialStatementService.incomeStatement
 * （mock，verify 参数透传）/返回结构含 lines（currentPeriod/yearToDate 字符串）/netProfitCurrent/netProfitYtd/
 * period 缺失或格式错前置 fail 不调服务/IllegalArgumentException 映射 ToolResult.fail。
 */
class QueryIncomeStatementToolTest {

    private FinancialStatementService financialStatementService;
    private QueryIncomeStatementTool tool;
    private final ToolContext context = new ToolContext("session-is", "5", "查利润表");

    @BeforeEach
    void setUp() {
        financialStatementService = mock(FinancialStatementService.class);
        tool = new QueryIncomeStatementTool(financialStatementService);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_income_statement");
    }

    @Test
    void 风险级别NORMAL_权限点finance_report() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("finance:report");
    }

    @Test
    void parameterSchema_period必填_pattern为6位数字() {
        String schema = tool.parameterSchema();
        assertThat(schema).contains("\"period\"");
        assertThat(schema).contains("^[0-9]{6}$");
        assertThat(schema).contains("\"required\":[\"period\"]");
        assertThat(schema).contains("\"additionalProperties\":false");
    }

    @Test
    void schema校验_合法period通过() {
        List<String> ok = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("period", "202606"));
        assertThat(ok).isEmpty();
    }

    // ================================================================== execute 成功

    @Test
    void 指定period_参数透传_返回利润表行与净利润() {
        IncomeStatement is = stubIncomeStatement("202606");
        when(financialStatementService.incomeStatement(eq("202606"))).thenReturn(is);

        ToolResult result = tool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("period")).isEqualTo("202606");
        assertThat(result.data()).containsKey("lines");
        assertThat(result.data()).containsKey("netProfitCurrent");
        assertThat(result.data()).containsKey("netProfitYtd");

        // 净利润为字符串
        assertThat(result.data().get("netProfitCurrent")).isEqualTo("8000.00");
        assertThat(result.data().get("netProfitYtd")).isEqualTo("45000.00");

        // 明细行结构
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) result.data().get("lines");
        assertThat(lines).hasSize(2);
        Map<String, Object> revenueLine = lines.get(0);
        assertThat(revenueLine.get("name")).isEqualTo("营业收入");
        assertThat(revenueLine.get("currentPeriod")).isEqualTo("50000.00");
        assertThat(revenueLine.get("yearToDate")).isEqualTo("280000.00");

        verify(financialStatementService).incomeStatement(eq("202606"));
    }

    // ================================================================== execute 失败（前置 fail）

    @Test
    void period缺失_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(financialStatementService);
    }

    @Test
    void period格式错误_含字母_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "2026AB"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(financialStatementService);
    }

    @Test
    void period格式错误_5位_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "20266"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(financialStatementService);
    }

    // ================================================================== 异常映射

    @Test
    void 服务抛IllegalArgumentException_映射为ToolResult_fail() {
        when(financialStatementService.incomeStatement(eq("202612")))
                .thenThrow(new IllegalArgumentException("账期未开启"));

        ToolResult result = tool.execute(Map.of("period", "202612"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("查询利润表失败");
        assertThat(result.error()).contains("账期未开启");
    }

    // ================================================================== 辅助方法

    private static IncomeStatement stubIncomeStatement(String period) {
        List<IncomeStatementLine> lines = List.of(
                new IncomeStatementLine("营业收入", "50000.00", "280000.00"),
                new IncomeStatementLine("营业成本", "42000.00", "235000.00"));
        return new IncomeStatement(period, lines, "8000.00", "45000.00");
    }
}
