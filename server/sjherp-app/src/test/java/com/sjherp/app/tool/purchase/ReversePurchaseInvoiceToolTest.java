package com.sjherp.app.tool.purchase;

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
import com.sjherp.app.purchase.PurchaseInvoiceAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.purchase.PurchaseInvoice;
import com.sjherp.domain.purchase.PurchaseInvoiceLine;
import com.sjherp.domain.purchase.PurchaseInvoiceNotFoundException;

/**
 * 冲销采购发票工具单测（M4-T07b，HIGH HITL）：name/riskLevel=HIGH/requiredPermission=purchase:invoice/
 * parameterSchema、execute 透传、返回数据、异常映射 fail（NotFound/PeriodClosed/IllegalState[含应付已核销
 * 须先冲付款单]/IllegalArgument）。照 {@code ReverseVoucherToolTest} 范式。
 */
class ReversePurchaseInvoiceToolTest {

    private PurchaseInvoiceAppService appService;
    private ReversePurchaseInvoiceTool tool;
    private final ToolContext context = new ToolContext("session-pinv", "5", "冲销采购发票");

    @BeforeEach
    void setUp() {
        appService = mock(PurchaseInvoiceAppService.class);
        tool = new ReversePurchaseInvoiceTool(appService);
    }

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_purchase_invoice");
    }

    @Test
    void 风险级别HIGH_权限点purchase_invoice() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("purchase:invoice");
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
        when(appService.reverse("PINV-202606-0001", "agent:5")).thenReturn(reversedInvoice());

        ToolResult result = tool.execute(Map.of("doc_no", "PINV-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "PINV-202606-0001");
        assertThat(result.data()).containsEntry("status", "REVERSED");
        assertThat(result.data()).containsKey("note");
        verify(appService).reverse("PINV-202606-0001", "agent:5");
    }

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(appService);
    }

    @Test
    void 发票不存在_转fail() {
        when(appService.reverse(eq("PINV-999999-0001"), any()))
                .thenThrow(new PurchaseInvoiceNotFoundException("PINV-999999-0001"));
        ToolResult result = tool.execute(Map.of("doc_no", "PINV-999999-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 账期已关账_转fail() {
        when(appService.reverse(eq("PINV-202605-0001"), any()))
                .thenThrow(new PeriodClosedException("202605"));
        ToolResult result = tool.execute(Map.of("doc_no", "PINV-202605-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("账期已关账");
    }

    @Test
    void 应付已核销须先冲付款单_转fail() {
        // 应付已核销不可冲（canBeReversed=false）→ AppService 前置 IllegalStateException
        when(appService.reverse(eq("PINV-202606-0001"), any()))
                .thenThrow(new IllegalStateException("应付[PINV-202606-0001] 已核销不可冲销，请先冲对应付款单"));
        ToolResult result = tool.execute(Map.of("doc_no", "PINV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 非法流转_转fail() {
        when(appService.reverse(eq("PINV-202606-0001"), any()))
                .thenThrow(new IllegalStateTransitionException("PINV-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));
        ToolResult result = tool.execute(Map.of("doc_no", "PINV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(appService.reverse(eq("PINV-202606-0001"), any()))
                .thenThrow(new IllegalArgumentException("红字关联单据号不能为空"));
        ToolResult result = tool.execute(Map.of("doc_no", "PINV-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    /** 已转 REVERSED 的采购发票 stub。 */
    private static PurchaseInvoice reversedInvoice() {
        PurchaseInvoiceLine line = PurchaseInvoiceLine.restore(1L, 1, 1, 100L,
                new BigDecimal("60"), new BigDecimal("750.00"));
        return PurchaseInvoice.restore("PINV-202606-0001", "PR-202606-0001", 1L,
                LocalDate.of(2026, 6, 14), null, null, DocumentStatus.REVERSED, List.of(line), "agent:5");
    }
}
