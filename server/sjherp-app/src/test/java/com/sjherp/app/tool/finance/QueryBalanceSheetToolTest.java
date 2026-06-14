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
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheet;
import com.sjherp.app.finance.FinancialStatementDtos.BalanceSheetLine;
import com.sjherp.app.finance.FinancialStatementService;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * query_balance_sheet 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:report/
 * parameterSchema（period 必填、^[0-9]{6}$ pattern）/execute 调 FinancialStatementService.balanceSheet
 * （mock，verify 参数透传）/返回结构含 assetLines/liabilityLines/equityLines/totalAssets(字符串)/balanced/
 * period 缺失或格式错前置 fail 不调服务/IllegalArgumentException 映射 ToolResult.fail。
 */
class QueryBalanceSheetToolTest {

    private FinancialStatementService financialStatementService;
    private QueryBalanceSheetTool tool;
    private final ToolContext context = new ToolContext("session-bs", "4", "查资产负债表");

    @BeforeEach
    void setUp() {
        financialStatementService = mock(FinancialStatementService.class);
        tool = new QueryBalanceSheetTool(financialStatementService);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_balance_sheet");
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
    void schema校验_合法period通过_缺period有错() {
        List<String> ok = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("period", "202606"));
        assertThat(ok).isEmpty();

        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of());
        assertThat(errors).isNotEmpty();
    }

    // ================================================================== execute 成功

    @Test
    void 指定period_参数透传_返回三大类报表行与汇总() {
        BalanceSheet bs = stubBalanceSheet("202606");
        when(financialStatementService.balanceSheet(eq("202606"))).thenReturn(bs);

        ToolResult result = tool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsKey("period");
        assertThat(result.data().get("period")).isEqualTo("202606");
        assertThat(result.data()).containsKey("assetLines");
        assertThat(result.data()).containsKey("totalAssets");
        assertThat(result.data()).containsKey("liabilityLines");
        assertThat(result.data()).containsKey("totalLiabilities");
        assertThat(result.data()).containsKey("equityLines");
        assertThat(result.data()).containsKey("totalEquity");
        assertThat(result.data()).containsKey("balanced");

        // totalAssets 已为字符串（FinancialStatementService 调用 toPlainString）
        assertThat(result.data().get("totalAssets")).isEqualTo("100000.00");
        assertThat(result.data().get("totalLiabilities")).isEqualTo("60000.00");
        assertThat(result.data().get("totalEquity")).isEqualTo("40000.00");
        assertThat(result.data().get("balanced")).isEqualTo(true);

        // 验证资产明细行
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assetLines =
                (List<Map<String, Object>>) result.data().get("assetLines");
        assertThat(assetLines).hasSize(1);
        assertThat(assetLines.get(0).get("name")).isEqualTo("货币资金");
        assertThat(assetLines.get(0).get("amount")).isEqualTo("100000.00");

        verify(financialStatementService).balanceSheet(eq("202606"));
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
    void period为空白_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", ""), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(financialStatementService);
    }

    @Test
    void period格式错误_7位数字_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "2026060"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(financialStatementService);
    }

    @Test
    void period格式错误_含连字符_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "2026-06"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(financialStatementService);
    }

    // ================================================================== 异常映射

    @Test
    void 服务抛IllegalArgumentException_映射为ToolResult_fail() {
        when(financialStatementService.balanceSheet(eq("202606")))
                .thenThrow(new IllegalArgumentException("账期 202606 不存在"));

        ToolResult result = tool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("查询资产负债表失败");
        assertThat(result.error()).contains("账期 202606 不存在");
    }

    // ================================================================== 辅助方法

    private static BalanceSheet stubBalanceSheet(String period) {
        List<BalanceSheetLine> assets = List.of(new BalanceSheetLine("货币资金", "100000.00"));
        List<BalanceSheetLine> liabilities = List.of(new BalanceSheetLine("短期借款", "60000.00"));
        List<BalanceSheetLine> equity = List.of(new BalanceSheetLine("实收资本", "40000.00"));
        return new BalanceSheet(period, assets, "100000.00", liabilities, "60000.00",
                equity, "40000.00", true);
    }
}
