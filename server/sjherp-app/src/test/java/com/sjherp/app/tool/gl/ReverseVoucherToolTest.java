package com.sjherp.app.tool.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLine;
import com.sjherp.domain.gl.VoucherNotFoundException;

/**
 * 冲销凭证工具单测（M4-T07a，HIGH HITL）：name/riskLevel=HIGH/requiredPermission=finance:voucher/
 * parameterSchema、execute 透传 doc_no 与 operator 前缀、返回红字凭证数据、异常映射 fail
 * （NotFound/PeriodClosed/IllegalState(Transition)/IllegalArgument）。
 *
 * <p>照既有 HIGH 工具单测范式（{@code PeriodCloseToolsTest}）：mock {@link VoucherAppService}、
 * verify 透传、错误路径转 {@link ToolResult#fail}。HITL 框架级确认由 AgentLoop 据 riskLevel 拦截，
 * 不在工具内实现，故此处只断言 riskLevel=HIGH。
 */
class ReverseVoucherToolTest {

    private VoucherAppService voucherAppService;
    private ReverseVoucherTool tool;
    // userId=5 → operator 应为 agent:5（ArchiveToolSupport.operator 约定）
    private final ToolContext context = new ToolContext("session-gl", "5", "冲销凭证");

    @BeforeEach
    void setUp() {
        voucherAppService = mock(VoucherAppService.class);
        tool = new ReverseVoucherTool(voucherAppService);
    }

    // ============================================================== 元数据

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_voucher");
    }

    @Test
    void 风险级别HIGH_权限点finance_voucher() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("finance:voucher");
    }

    @Test
    void 入参schema含doc_no必填() {
        String schema = tool.parameterSchema();
        assertThat(schema).contains("\"doc_no\"");
        assertThat(schema).contains("\"required\":[\"doc_no\"]");
        assertThat(schema).contains("additionalProperties\":false");
    }

    @Test
    void description复述不可逆与确认要点() {
        String description = tool.description();
        assertThat(description).contains("不可逆");
        assertThat(description).contains("确认");
        assertThat(description).contains("红字");
    }

    // ============================================================== execute 成功

    @Test
    void 正常调用_透传doc_no与operator前缀_返回红字数据() {
        when(voucherAppService.reverse("VCH-202606-0002", "agent:5"))
                .thenReturn(reversalVoucher());

        ToolResult result = tool.execute(Map.of("doc_no", "VCH-202606-0002"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("originalDocNo", "VCH-202606-0002");
        assertThat(result.data()).containsEntry("reversalDocNo", "VCH-202606-9001");
        assertThat(result.data()).containsEntry("status", "APPROVED");
        assertThat(result.data()).containsEntry("totalAmount", "500.00");
        assertThat(result.data()).containsKey("note");
        verify(voucherAppService).reverse("VCH-202606-0002", "agent:5");
    }

    /** 借贷对调红字（VOUCHER_REVERSAL/原号、APPROVED）凭证 stub。 */
    private static Voucher reversalVoucher() {
        List<VoucherLine> lines = List.of(
                VoucherLine.restore(1L, 1, "1001", new BigDecimal("0.00"), new BigDecimal("500.00"), "冲销:"),
                VoucherLine.restore(2L, 2, "6001", new BigDecimal("500.00"), new BigDecimal("0.00"), "冲销:"));
        return Voucher.restore("VCH-202606-9001", "202606", LocalDate.of(2026, 6, 2), "记",
                new BigDecimal("500.00"), "冲销 VCH-202606-0002",
                "VOUCHER_REVERSAL", "VCH-202606-0002",
                DocumentStatus.APPROVED, lines, "agent:5");
    }

    // ============================================================== execute 失败

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(voucherAppService);
    }

    @Test
    void 原凭证不存在_转fail() {
        when(voucherAppService.reverse(eq("VCH-999999-0001"), any()))
                .thenThrow(new VoucherNotFoundException("VCH-999999-0001"));

        ToolResult result = tool.execute(Map.of("doc_no", "VCH-999999-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 账期已关账_转fail() {
        when(voucherAppService.reverse(eq("VCH-202605-0001"), any()))
                .thenThrow(new PeriodClosedException("202605"));

        ToolResult result = tool.execute(Map.of("doc_no", "VCH-202605-0001"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("账期已关账");
    }

    @Test
    void 非法状态_已冲销_转fail() {
        when(voucherAppService.reverse(eq("VCH-202606-0002"), any()))
                .thenThrow(new IllegalStateException("凭证已冲销，红字号=VCH-202606-9001，不可重复冲销"));

        ToolResult result = tool.execute(Map.of("doc_no", "VCH-202606-0002"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 非法流转_转fail() {
        when(voucherAppService.reverse(eq("VCH-202606-0002"), any()))
                .thenThrow(new IllegalStateTransitionException("VCH-202606-0002",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));

        ToolResult result = tool.execute(Map.of("doc_no", "VCH-202606-0002"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(voucherAppService.reverse(eq("VCH-202606-0002"), any()))
                .thenThrow(new IllegalArgumentException("原凭证号不能为空"));

        ToolResult result = tool.execute(Map.of("doc_no", "VCH-202606-0002"), context);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }
}
