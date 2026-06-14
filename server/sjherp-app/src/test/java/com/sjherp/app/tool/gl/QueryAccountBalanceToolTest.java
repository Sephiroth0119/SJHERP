package com.sjherp.app.tool.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;

/**
 * query_account_balance 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:voucher/
 * parameterSchema（accountCode+period 双必填，^[0-9]{6}$ pattern）/execute 调
 * VoucherAppService.accountBalance（mock，verify accountCode/period 双参透传）/
 * 返回结构含 accountCode/totalDebit/totalCredit/netBalance（字符串）/
 * accountCode 或 period 缺失/格式错前置 fail 不调服务/IllegalArgumentException 映射 fail。
 */
class QueryAccountBalanceToolTest {

    private VoucherAppService voucherAppService;
    private QueryAccountBalanceTool tool;
    private final ToolContext context = new ToolContext("session-ab", "9", "查科目余额");

    @BeforeEach
    void setUp() {
        voucherAppService = mock(VoucherAppService.class);
        tool = new QueryAccountBalanceTool(voucherAppService);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_account_balance");
    }

    @Test
    void 风险级别NORMAL_权限点finance_voucher() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("finance:voucher");
    }

    @Test
    void parameterSchema_accountCode和period双必填_period有pattern() {
        String schema = tool.parameterSchema();
        assertThat(schema).contains("\"accountCode\"");
        assertThat(schema).contains("\"period\"");
        assertThat(schema).contains("^[0-9]{6}$");
        assertThat(schema).contains("\"required\":[\"accountCode\",\"period\"]");
        assertThat(schema).contains("\"additionalProperties\":false");
    }

    @Test
    void schema校验_合法参数通过() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(),
                        Map.of("accountCode", "1122", "period", "202606"));
        assertThat(errors).isEmpty();
    }

    @Test
    void schema校验_缺period有错() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("accountCode", "1122"));
        assertThat(errors).isNotEmpty();
    }

    @Test
    void schema校验_缺accountCode有错() {
        List<String> errors = new JsonSchemaToolArgumentValidator()
                .validate(tool.parameterSchema(), Map.of("period", "202606"));
        assertThat(errors).isNotEmpty();
    }

    // ================================================================== execute 成功

    @Test
    void 指定accountCode和period_参数透传_返回借贷净额() {
        AccountBalance ab = new AccountBalance("1122",
                new BigDecimal("50000.00"), new BigDecimal("30000.00"));
        when(voucherAppService.accountBalance(eq("1122"), eq("202606"))).thenReturn(ab);

        ToolResult result = tool.execute(
                Map.of("accountCode", "1122", "period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("accountCode")).isEqualTo("1122");
        assertThat(result.data().get("totalDebit")).isEqualTo("50000.00");
        assertThat(result.data().get("totalCredit")).isEqualTo("30000.00");
        // netBalance = 50000 - 30000 = 20000
        assertThat(result.data().get("netBalance")).isEqualTo("20000.00");

        verify(voucherAppService).accountBalance(eq("1122"), eq("202606"));
    }

    @Test
    void 净额为负_toPlainString正确输出负数() {
        // 贷方 > 借方，净额为负（如负债科目）
        AccountBalance ab = new AccountBalance("220202",
                new BigDecimal("0.00"), new BigDecimal("8000.00"));
        when(voucherAppService.accountBalance(eq("220202"), eq("202606"))).thenReturn(ab);

        ToolResult result = tool.execute(
                Map.of("accountCode", "220202", "period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("netBalance")).isEqualTo("-8000.00");
    }

    // ================================================================== execute 失败（前置 fail）

    @Test
    void accountCode缺失_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("accountCode");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void accountCode为空白_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("accountCode", "", "period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("accountCode");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void period缺失_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("accountCode", "1122"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void period格式错误_含连字符_前置fail_不调服务() {
        ToolResult result = tool.execute(
                Map.of("accountCode", "1122", "period", "2026-06"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void period格式错误_5位_前置fail_不调服务() {
        ToolResult result = tool.execute(
                Map.of("accountCode", "1122", "period", "20266"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(voucherAppService);
    }

    // ================================================================== 异常映射

    @Test
    void 服务抛IllegalArgumentException_映射为ToolResult_fail() {
        when(voucherAppService.accountBalance(eq("9999"), eq("202606")))
                .thenThrow(new IllegalArgumentException("科目 9999 不存在"));

        ToolResult result = tool.execute(
                Map.of("accountCode", "9999", "period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("查询科目余额失败");
        assertThat(result.error()).contains("科目 9999 不存在");
    }
}
