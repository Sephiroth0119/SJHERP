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
import com.sjherp.app.purchase.PurchaseReceiptAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.purchase.PurchaseReceipt;
import com.sjherp.domain.purchase.PurchaseReceiptLine;
import com.sjherp.domain.purchase.PurchaseReceiptNotFoundException;

/**
 * 冲销采购入库单工具单测（M4-T07b，HIGH HITL）：name/riskLevel=HIGH/requiredPermission=purchase:receipt/
 * parameterSchema、execute 透传 doc_no 与 operator 前缀、返回数据、异常映射 fail
 * （NotFound/PeriodClosed/IllegalState(Transition)/IllegalArgument）。
 *
 * <p>照既有 HIGH 工具单测范式（{@code ReverseVoucherToolTest}）：mock AppService、verify 透传、
 * 错误路径转 {@link ToolResult#fail}。HITL 框架级确认由 AgentLoop 据 riskLevel 拦截，此处只断言 HIGH。
 */
class ReversePurchaseReceiptToolTest {

    private PurchaseReceiptAppService appService;
    private ReversePurchaseReceiptTool tool;
    // userId=5 → operator agent:5（ArchiveToolSupport.operator 约定）
    private final ToolContext context = new ToolContext("session-pr", "5", "冲销采购入库");

    @BeforeEach
    void setUp() {
        appService = mock(PurchaseReceiptAppService.class);
        tool = new ReversePurchaseReceiptTool(appService);
    }

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_purchase_receipt");
    }

    @Test
    void 风险级别HIGH_权限点purchase_receipt() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("purchase:receipt");
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
        when(appService.reverse("PR-202606-0001", "agent:5")).thenReturn(reversedReceipt());

        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "PR-202606-0001");
        assertThat(result.data()).containsEntry("status", "REVERSED");
        assertThat(result.data()).containsKey("note");
        verify(appService).reverse("PR-202606-0001", "agent:5");
    }

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(appService);
    }

    @Test
    void 入库单不存在_转fail() {
        when(appService.reverse(eq("PR-999999-0001"), any()))
                .thenThrow(new PurchaseReceiptNotFoundException("PR-999999-0001"));
        ToolResult result = tool.execute(Map.of("doc_no", "PR-999999-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 账期已关账_转fail() {
        when(appService.reverse(eq("PR-202605-0001"), any()))
                .thenThrow(new PeriodClosedException("202605"));
        ToolResult result = tool.execute(Map.of("doc_no", "PR-202605-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("账期已关账");
    }

    @Test
    void 非法状态_已冲销_转fail() {
        when(appService.reverse(eq("PR-202606-0001"), any()))
                .thenThrow(new IllegalStateException("采购入库单[PR-202606-0001] 已冲销，不可重复冲销"));
        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 非法流转_转fail() {
        when(appService.reverse(eq("PR-202606-0001"), any()))
                .thenThrow(new IllegalStateTransitionException("PR-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));
        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(appService.reverse(eq("PR-202606-0001"), any()))
                .thenThrow(new IllegalArgumentException("红字关联单据号不能为空"));
        ToolResult result = tool.execute(Map.of("doc_no", "PR-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    /** 已转 REVERSED 的采购入库单 stub。 */
    private static PurchaseReceipt reversedReceipt() {
        PurchaseReceiptLine line = PurchaseReceiptLine.restore(1L, 1, 1, 100L,
                new BigDecimal("60"), new BigDecimal("12.5"), new BigDecimal("750.00"),
                BigDecimal.ZERO);
        return PurchaseReceipt.restore("PR-202606-0001", "PO-202606-0001", 10L,
                LocalDate.of(2026, 6, 13), null, DocumentStatus.REVERSED, List.of(line), "agent:5");
    }
}
