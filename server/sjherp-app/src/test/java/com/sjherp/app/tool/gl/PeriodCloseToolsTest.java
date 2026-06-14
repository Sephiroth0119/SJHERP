package com.sjherp.app.tool.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.sjherp.app.gl.GlDtos.ClosingPreviewLine;
import com.sjherp.app.gl.GlDtos.PeriodCloseReadiness;
import com.sjherp.app.gl.GlDtos.PeriodCloseResult;
import com.sjherp.app.gl.PeriodCloseBlockedException;
import com.sjherp.app.gl.PeriodCloseService;
import com.sjherp.domain.gl.AccountingPeriodNotFoundException;
import com.sjherp.domain.gl.PeriodClosedException;

/**
 * 月末关账工具组单测（M4-T05）：precheck_period_close（NORMAL，只读）+
 * close_accounting_period（HIGH，不可逆关账）的风险级别 / 权限点 / 入参 schema / period 透传 /
 * operator 前缀 / 异常映射（PeriodCloseBlocked 携 reasons、PeriodClosed、NotFound、IAE/ISE）。
 *
 * <p>照采购工具单测范式（{@code PurchaseInvoiceToolsTest} 等）：mock {@link PeriodCloseService}，
 * verify 透传，错误路径转 {@link ToolResult#fail}。
 */
class PeriodCloseToolsTest {

    private PeriodCloseService periodCloseService;
    private PrecheckPeriodCloseTool precheckTool;
    private CloseAccountingPeriodTool closeTool;
    // userId=5 → operator 应为 agent:5（ArchiveToolSupport.operator 约定）
    private final ToolContext context = new ToolContext("session-gl", "5", "月末关账");

    @BeforeEach
    void setUp() {
        periodCloseService = mock(PeriodCloseService.class);
        precheckTool = new PrecheckPeriodCloseTool(periodCloseService);
        closeTool = new CloseAccountingPeriodTool(periodCloseService);
    }

    private static PeriodCloseReadiness readiness(boolean closeable) {
        return new PeriodCloseReadiness("202606", "OPEN", closeable, false,
                List.of(), List.of(),
                List.of(new ClosingPreviewLine("6001", "主营业务收入", "1000.00", "0.00"),
                        new ClosingPreviewLine("6401", "主营业务成本", "0.00", "600.00")),
                "1000.00", "600.00", "400.00", "5000.00", "5000.00");
    }

    private static PeriodCloseResult result() {
        return new PeriodCloseResult("202606", "VCH-202606-0009", "1000.00", "600.00",
                "400.00", "5000.00", "5000.00", "agent:5", "2026-06-30T00:00:00Z");
    }

    // ============================================================== precheck 元数据

    @Test
    void precheck_name() {
        assertThat(precheckTool.name()).isEqualTo("precheck_period_close");
    }

    @Test
    void precheck_风险级别NORMAL_权限点finance_period() {
        assertThat(precheckTool.riskLevel()).isEqualTo(ToolRiskLevel.NORMAL);
        assertThat(precheckTool.requiredPermission()).isEqualTo("finance:period");
    }

    @Test
    void precheck_入参schema含period必填且pattern六位数字() {
        String schema = precheckTool.parameterSchema();
        assertThat(schema).contains("\"period\"");
        assertThat(schema).contains("^[0-9]{6}$");
        assertThat(schema).contains("\"required\":[\"period\"]");
        assertThat(schema).contains("additionalProperties\":false");
    }

    // ============================================================== precheck execute

    @Test
    void precheck_正常调用_透传period_返回readiness字段() {
        when(periodCloseService.precheck("202606")).thenReturn(readiness(true));

        ToolResult result = precheckTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("period", "202606");
        assertThat(result.data()).containsEntry("status", "OPEN");
        assertThat(result.data()).containsEntry("closeable", true);
        assertThat(result.data()).containsEntry("netProfit", "400.00");
        assertThat(result.data()).containsKey("consistencyErrors");
        assertThat(result.data()).containsKey("consistencyWarnings");
        assertThat(result.data()).containsKey("closingPreviewLines");
        verify(periodCloseService).precheck("202606");
    }

    @Test
    @SuppressWarnings("unchecked")
    void precheck_结转预览行展开为可读结构() {
        when(periodCloseService.precheck("202606")).thenReturn(readiness(true));

        ToolResult result = precheckTool.execute(Map.of("period", "202606"), context);

        List<Map<String, Object>> lines =
                (List<Map<String, Object>>) result.data().get("closingPreviewLines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("accountCode", "6001");
        assertThat(lines.get(0)).containsEntry("accountName", "主营业务收入");
        assertThat(lines.get(0)).containsEntry("debit", "1000.00");
        assertThat(lines.get(0)).containsEntry("credit", "0.00");
    }

    @Test
    void precheck_不可关账_note提示阻断() {
        when(periodCloseService.precheck("202606")).thenReturn(readiness(false));

        ToolResult result = precheckTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("closeable", false);
        assertThat(result.data().get("note").toString()).contains("不可关账");
    }

    @Test
    void precheck_period缺失_失败_不调服务() {
        ToolResult result = precheckTool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verifyNoInteractions(periodCloseService);
    }

    @Test
    void precheck_账期不存在_转fail() {
        when(periodCloseService.precheck("202601"))
                .thenThrow(new AccountingPeriodNotFoundException("202601"));

        ToolResult result = precheckTool.execute(Map.of("period", "202601"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void precheck_参数非法_转fail() {
        when(periodCloseService.precheck(any()))
                .thenThrow(new IllegalArgumentException("账期格式非法"));

        ToolResult result = precheckTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("被拒绝");
    }

    // ============================================================== close 元数据

    @Test
    void close_name() {
        assertThat(closeTool.name()).isEqualTo("close_accounting_period");
    }

    @Test
    void close_风险级别HIGH_权限点finance_period() {
        assertThat(closeTool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(closeTool.requiredPermission()).isEqualTo("finance:period");
    }

    @Test
    void close_入参schema含period必填且pattern六位数字() {
        String schema = closeTool.parameterSchema();
        assertThat(schema).contains("\"period\"");
        assertThat(schema).contains("^[0-9]{6}$");
        assertThat(schema).contains("\"required\":[\"period\"]");
        assertThat(schema).contains("additionalProperties\":false");
    }

    @Test
    void close_description复述不可逆关账要点() {
        String description = closeTool.description();
        assertThat(description).contains("不可逆");
        assertThat(description).contains("4103");
        assertThat(description).contains("确认");
    }

    // ============================================================== close execute

    @Test
    void close_正常调用_透传period与operator前缀_返回result字段() {
        when(periodCloseService.close("202606", "agent:5")).thenReturn(result());

        ToolResult result = closeTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("period", "202606");
        assertThat(result.data()).containsEntry("closingVoucherDocNo", "VCH-202606-0009");
        assertThat(result.data()).containsEntry("netProfit", "400.00");
        assertThat(result.data()).containsEntry("closedBy", "agent:5");
        assertThat(result.data()).containsKey("note");
        verify(periodCloseService).close("202606", "agent:5");
    }

    @Test
    void close_period缺失_失败_不调服务() {
        ToolResult result = closeTool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("period");
        verify(periodCloseService, never()).close(any(), any());
    }

    @Test
    void close_闸门拒绝_返回fail且data携reasons清单() {
        List<String> reasons = List.of(
                "[INVENTORY_LEDGER] SKU-001 账实不平（期望=100, 实际=98）",
                "[SETTLEMENT_ROLLUP] AR-3 核销额不一致（期望=200.00, 实际=150.00）");
        when(periodCloseService.close(eq("202606"), any()))
                .thenThrow(new PeriodCloseBlockedException("账期[202606] 存在 2 项数据一致性错误", reasons));

        ToolResult result = closeTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("关账被拒绝");
        // 区别于普通 fail（data==Map.of()）：blocked 路径 data 携 period + reasons 清单供 Agent 复述
        assertThat(result.data()).containsEntry("period", "202606");
        assertThat(result.data()).containsEntry("reasons", reasons);
    }

    @Test
    void close_闸门拒绝单原因_reasons非空() {
        when(periodCloseService.close(eq("202606"), any()))
                .thenThrow(new PeriodCloseBlockedException(
                        "账期[202606] 当前状态为 已关账，仅 OPEN 账期可关账"));

        ToolResult result = closeTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.data()).containsKey("reasons");
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) result.data().get("reasons");
        assertThat(reasons).isNotEmpty();
    }

    @Test
    void close_账期已关闭_转fail() {
        when(periodCloseService.close(eq("202606"), any()))
                .thenThrow(new PeriodClosedException("202606"));

        ToolResult result = closeTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("已关闭");
    }

    @Test
    void close_账期不存在_转fail() {
        when(periodCloseService.close(eq("202601"), any()))
                .thenThrow(new AccountingPeriodNotFoundException("202601"));

        ToolResult result = closeTool.execute(Map.of("period", "202601"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void close_试算断言兜底_IllegalState转fail() {
        when(periodCloseService.close(eq("202606"), any()))
                .thenThrow(new IllegalStateException("账期[202606] 结转后试算不平"));

        ToolResult result = closeTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("关账被拒绝");
    }

    @Test
    void close_参数非法_IllegalArgument转fail() {
        when(periodCloseService.close(eq("202606"), any()))
                .thenThrow(new IllegalArgumentException("operator 不能为空"));

        ToolResult result = closeTool.execute(Map.of("period", "202606"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("关账被拒绝");
    }
}
