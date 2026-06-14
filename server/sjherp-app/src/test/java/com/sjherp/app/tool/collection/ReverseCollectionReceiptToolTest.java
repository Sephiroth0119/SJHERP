package com.sjherp.app.tool.collection;

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
import com.sjherp.app.collection.CollectionReceiptAppService;
import com.sjherp.domain.collection.CollectionReceipt;
import com.sjherp.domain.collection.CollectionReceiptLine;
import com.sjherp.domain.collection.CollectionReceiptNotFoundException;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.PeriodClosedException;

/**
 * 冲销收款单工具单测（M4-T07c，HIGH HITL）：name/riskLevel=HIGH/requiredPermission=finance:settlement/
 * parameterSchema 含 doc_no 必填、execute 透传 doc_no 与 operator 前缀、返回数据、异常映射 fail
 * （NotFound/PeriodClosed/IllegalState(Transition)/IllegalArgument）。
 *
 * <p>照既有 HIGH 工具单测范式（{@code ReversePurchaseReceiptToolTest}）：mock AppService、verify 透传、
 * 错误路径转 {@link ToolResult#fail}。HITL 框架级确认由 AgentLoop 据 riskLevel 拦截，此处只断言 HIGH。
 */
class ReverseCollectionReceiptToolTest {

    private CollectionReceiptAppService appService;
    private ReverseCollectionReceiptTool tool;
    // userId=5 → operator agent:5（ArchiveToolSupport.operator 约定）
    private final ToolContext context = new ToolContext("session-rcpt", "5", "冲销收款单");

    @BeforeEach
    void setUp() {
        appService = mock(CollectionReceiptAppService.class);
        tool = new ReverseCollectionReceiptTool(appService);
    }

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_collection_receipt");
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
        when(appService.reverse("RCPT-202606-0001", "agent:5")).thenReturn(reversedReceipt());

        ToolResult result = tool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "RCPT-202606-0001");
        assertThat(result.data()).containsEntry("status", "REVERSED");
        assertThat(result.data()).containsKey("note");
        verify(appService).reverse("RCPT-202606-0001", "agent:5");
    }

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(appService);
    }

    @Test
    void 收款单不存在_转fail() {
        when(appService.reverse(eq("RCPT-999999-0001"), any()))
                .thenThrow(new CollectionReceiptNotFoundException("RCPT-999999-0001"));
        ToolResult result = tool.execute(Map.of("doc_no", "RCPT-999999-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 账期已关账_转fail提示先重开() {
        when(appService.reverse(eq("RCPT-202605-0001"), any()))
                .thenThrow(new PeriodClosedException("202605"));
        ToolResult result = tool.execute(Map.of("doc_no", "RCPT-202605-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("账期已关账");
    }

    @Test
    void 非法状态_已冲销_转fail() {
        when(appService.reverse(eq("RCPT-202606-0001"), any()))
                .thenThrow(new IllegalStateException("收款单[RCPT-202606-0001] 已冲销，不可重复冲销"));
        ToolResult result = tool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 非法流转_转fail() {
        when(appService.reverse(eq("RCPT-202606-0001"), any()))
                .thenThrow(new IllegalStateTransitionException("RCPT-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));
        ToolResult result = tool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(appService.reverse(eq("RCPT-202606-0001"), any()))
                .thenThrow(new IllegalArgumentException("红字关联单据号不能为空"));
        ToolResult result = tool.execute(Map.of("doc_no", "RCPT-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    /** 已转 REVERSED 的收款单 stub。 */
    private static CollectionReceipt reversedReceipt() {
        CollectionReceiptLine line = CollectionReceiptLine.create(1, 100L, new BigDecimal("300.00"));
        return CollectionReceipt.restore("RCPT-202606-0001", 7L, 3L, LocalDate.of(2026, 6, 13),
                null, DocumentStatus.REVERSED, List.of(line), "agent:5");
    }
}
