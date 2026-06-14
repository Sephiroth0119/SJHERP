package com.sjherp.app.tool.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.sjherp.app.sales.SalesDeliveryAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.sales.SalesDelivery;
import com.sjherp.domain.sales.SalesDeliveryLine;
import com.sjherp.domain.sales.SalesDeliveryNotFoundException;

/**
 * 冲销销售出库单工具单测（M4-T07b，HIGH HITL）：name/riskLevel=HIGH/requiredPermission=sales:delivery/
 * parameterSchema、execute 透传、返回数据、异常映射 fail（NotFound/IllegalState[含 PeriodClosedException
 * 子类]/IllegalArgument）。照 {@code ReverseVoucherToolTest} 范式。
 */
class ReverseSalesDeliveryToolTest {

    private SalesDeliveryAppService appService;
    private ReverseSalesDeliveryTool tool;
    private final ToolContext context = new ToolContext("session-sd", "5", "冲销销售出库");

    @BeforeEach
    void setUp() {
        appService = mock(SalesDeliveryAppService.class);
        tool = new ReverseSalesDeliveryTool(appService);
    }

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_sales_delivery");
    }

    @Test
    void 风险级别HIGH_权限点sales_delivery() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("sales:delivery");
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
        when(appService.reverse("SD-202606-0001", "agent:5")).thenReturn(reversedDelivery());

        ToolResult result = tool.execute(Map.of("doc_no", "SD-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "SD-202606-0001");
        assertThat(result.data()).containsEntry("status", "REVERSED");
        assertThat(result.data()).containsEntry("reversedById", "VCH-RED-1");
        assertThat(result.data()).containsKey("note");
        verify(appService).reverse("SD-202606-0001", "agent:5");
    }

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(appService);
    }

    @Test
    void 出库单不存在_转fail() {
        when(appService.reverse(eq("SD-999999-0001"), any()))
                .thenThrow(new SalesDeliveryNotFoundException("SD-999999-0001"));
        ToolResult result = tool.execute(Map.of("doc_no", "SD-999999-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 账期已关账_转fail() {
        // PeriodClosedException 是 IllegalStateException 子类，落入「冲销销售出库单被拒绝」分支
        when(appService.reverse(eq("SD-202605-0001"), any()))
                .thenThrow(new PeriodClosedException("202605"));
        ToolResult result = tool.execute(Map.of("doc_no", "SD-202605-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销销售出库单被拒绝");
    }

    @Test
    void 非法流转_未过账_转fail() {
        when(appService.reverse(eq("SD-202606-0001"), any()))
                .thenThrow(new IllegalStateTransitionException("SD-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));
        ToolResult result = tool.execute(Map.of("doc_no", "SD-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销销售出库单被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(appService.reverse(eq("SD-202606-0001"), any()))
                .thenThrow(new IllegalArgumentException("红字关联单据号不能为空"));
        ToolResult result = tool.execute(Map.of("doc_no", "SD-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    /** 已转 REVERSED 的销售出库单 stub（reversedById = 红字凭证号）。 */
    private static SalesDelivery reversedDelivery() {
        SalesDeliveryLine line = SalesDeliveryLine.restore(1L, 1, 1, 100L, new BigDecimal("70"),
                new BigDecimal("700.00"), BigDecimal.ZERO);
        SalesDelivery delivery = SalesDelivery.restore("SD-202606-0001", "SO-202606-0001", 1L, null,
                DocumentStatus.COMPLETED, List.of(line), "agent:5");
        delivery.reverse("agent:5", "VCH-RED-1");
        return delivery;
    }
}
