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
 * query_trial_balance 工具单测（M4-T08）：name/riskLevel=NORMAL/requiredPermission=finance:voucher/
 * parameterSchema（period 必填、^[0-9]{6}$ pattern）/execute 调 VoucherAppService.trialBalance
 * （mock，verify period 透传）/返回结构含 lines（借贷净额字符串）/totalDebit/totalCredit/balanced/
 * period 缺失或格式错前置 fail 不调服务/balanced=true 当 Σ借==Σ贷/balanced=false 当不平。
 */
class QueryTrialBalanceToolTest {

    private VoucherAppService voucherAppService;
    private QueryTrialBalanceTool tool;
    private final ToolContext context = new ToolContext("session-tb", "8", "查试算平衡");

    @BeforeEach
    void setUp() {
        voucherAppService = mock(VoucherAppService.class);
        tool = new QueryTrialBalanceTool(voucherAppService);
    }

    // ================================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("query_trial_balance");
    }

    @Test
    void 风险级别NORMAL_权限点finance_voucher() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(tool.requiredPermission()).isEqualTo("finance:voucher");
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

    // ================================================================== execute 成功（借贷平衡）

    @Test
    void 借贷平衡_返回lines与汇总_balanced为true() {
        List<AccountBalance> balances = List.of(
                new AccountBalance("1001", new BigDecimal("10000.00"), new BigDecimal("2000.00")),
                new AccountBalance("2202", new BigDecimal("0.00"), new BigDecimal("8000.00")));
        when(voucherAppService.trialBalance(eq("202606"))).thenReturn(balances);

        ToolResult result = tool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("period")).isEqualTo("202606");

        // 明细行
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) result.data().get("lines");
        assertThat(lines).hasSize(2);
        Map<String, Object> line0 = lines.get(0);
        assertThat(line0.get("accountCode")).isEqualTo("1001");
        assertThat(line0.get("totalDebit")).isEqualTo("10000.00");
        assertThat(line0.get("totalCredit")).isEqualTo("2000.00");
        // netBalance = 借−贷 = 8000
        assertThat(line0.get("netBalance")).isEqualTo("8000.00");

        // 汇总：Σ借 = 10000, Σ贷 = 10000，balanced=true
        assertThat(result.data().get("totalDebit")).isEqualTo("10000.00");
        assertThat(result.data().get("totalCredit")).isEqualTo("10000.00");
        assertThat(result.data().get("balanced")).isEqualTo(true);

        verify(voucherAppService).trialBalance(eq("202606"));
    }

    @Test
    void 借贷不平_balanced为false() {
        // 构造不平衡的情况（实际业务不应发生，但工具应如实反映）
        List<AccountBalance> balances = List.of(
                new AccountBalance("1001", new BigDecimal("10000.00"), new BigDecimal("0.00")),
                new AccountBalance("2202", new BigDecimal("0.00"), new BigDecimal("9000.00")));
        when(voucherAppService.trialBalance(eq("202605"))).thenReturn(balances);

        ToolResult result = tool.execute(Map.of("period", "202605"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("totalDebit")).isEqualTo("10000.00");
        assertThat(result.data().get("totalCredit")).isEqualTo("9000.00");
        assertThat(result.data().get("balanced")).isEqualTo(false);
    }

    @Test
    void 账期无凭证_返回空lines_balanced为true() {
        when(voucherAppService.trialBalance(eq("202601"))).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of("period", "202601"), context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<?> lines = (List<?>) result.data().get("lines");
        assertThat(lines).isEmpty();
        assertThat(result.data().get("balanced")).isEqualTo(true);
    }

    // ================================================================== execute 失败（前置 fail）

    @Test
    void period缺失_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void period格式错误_7位数字_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "2026066"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void period格式错误_含连字符_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "2026-06"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void period为空白_前置fail_不调服务() {
        ToolResult result = tool.execute(Map.of("period", "   "), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(voucherAppService);
    }
}
