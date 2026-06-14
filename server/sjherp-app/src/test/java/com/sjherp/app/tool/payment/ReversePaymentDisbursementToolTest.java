package com.sjherp.app.tool.payment;

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
import com.sjherp.app.payment.PaymentDisbursementAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.payment.PaymentDisbursement;
import com.sjherp.domain.payment.PaymentDisbursementLine;
import com.sjherp.domain.payment.PaymentDisbursementNotFoundException;

/**
 * 冲销付款单工具单测（M4-T07c，HIGH HITL，对称收款单）：name/riskLevel=HIGH/
 * requiredPermission=finance:settlement/parameterSchema、execute 透传、返回数据、异常映射 fail。
 */
class ReversePaymentDisbursementToolTest {

    private PaymentDisbursementAppService appService;
    private ReversePaymentDisbursementTool tool;
    private final ToolContext context = new ToolContext("session-payv", "5", "冲销付款单");

    @BeforeEach
    void setUp() {
        appService = mock(PaymentDisbursementAppService.class);
        tool = new ReversePaymentDisbursementTool(appService);
    }

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_payment_disbursement");
    }

    @Test
    void 风险级别HIGH_权限点finance_settlement() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("finance:settlement");
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
    }

    @Test
    void 正常调用_透传doc_no与operator前缀_返回冲销数据() {
        when(appService.reverse("PAYV-202606-0001", "agent:5")).thenReturn(reversedDisbursement());

        ToolResult result = tool.execute(Map.of("doc_no", "PAYV-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "PAYV-202606-0001");
        assertThat(result.data()).containsEntry("status", "REVERSED");
        assertThat(result.data()).containsKey("note");
        verify(appService).reverse("PAYV-202606-0001", "agent:5");
    }

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(appService);
    }

    @Test
    void 付款单不存在_转fail() {
        when(appService.reverse(eq("PAYV-999999-0001"), any()))
                .thenThrow(new PaymentDisbursementNotFoundException("PAYV-999999-0001"));
        ToolResult result = tool.execute(Map.of("doc_no", "PAYV-999999-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 账期已关账_转fail提示先重开() {
        when(appService.reverse(eq("PAYV-202605-0001"), any()))
                .thenThrow(new PeriodClosedException("202605"));
        ToolResult result = tool.execute(Map.of("doc_no", "PAYV-202605-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("账期已关账");
    }

    @Test
    void 非法状态_已冲销_转fail() {
        when(appService.reverse(eq("PAYV-202606-0001"), any()))
                .thenThrow(new IllegalStateException("付款单[PAYV-202606-0001] 已冲销，不可重复冲销"));
        ToolResult result = tool.execute(Map.of("doc_no", "PAYV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 非法流转_转fail() {
        when(appService.reverse(eq("PAYV-202606-0001"), any()))
                .thenThrow(new IllegalStateTransitionException("PAYV-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));
        ToolResult result = tool.execute(Map.of("doc_no", "PAYV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(appService.reverse(eq("PAYV-202606-0001"), any()))
                .thenThrow(new IllegalArgumentException("红字关联单据号不能为空"));
        ToolResult result = tool.execute(Map.of("doc_no", "PAYV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    /** 已转 REVERSED 的付款单 stub。 */
    private static PaymentDisbursement reversedDisbursement() {
        PaymentDisbursementLine line = PaymentDisbursementLine.create(1, 100L, new BigDecimal("300.00"));
        return PaymentDisbursement.restore("PAYV-202606-0001", 1L, 10L, LocalDate.of(2026, 6, 13),
                null, DocumentStatus.REVERSED, List.of(line), "agent:5");
    }
}
