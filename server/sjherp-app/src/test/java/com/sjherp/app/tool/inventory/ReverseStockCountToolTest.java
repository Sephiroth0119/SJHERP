package com.sjherp.app.tool.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.stocktake.StocktakeService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountNotFoundException;

/**
 * 冲销盘点单工具单测（M4-T07c，HIGH HITL）：name/riskLevel=HIGH/requiredPermission=inventory:count/
 * parameterSchema、execute 透传、返回数据、异常映射 fail（NotFound/IllegalState(Transition)/IllegalArgument）。
 * 盘点不出 GL 凭证，故无 PeriodClosed 分支。
 */
class ReverseStockCountToolTest {

    private StocktakeService stocktakeService;
    private ReverseStockCountTool tool;
    private final ToolContext context = new ToolContext("session-sc", "5", "冲销盘点单");

    @BeforeEach
    void setUp() {
        stocktakeService = mock(StocktakeService.class);
        tool = new ReverseStockCountTool(stocktakeService);
    }

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_stock_count");
    }

    @Test
    void 风险级别HIGH_权限点inventory_count() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("inventory:count");
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
        StockCountDocument reversed = mock(StockCountDocument.class);
        when(reversed.getDocNo()).thenReturn("SC-202606-0001");
        when(reversed.getStatus()).thenReturn(DocumentStatus.REVERSED);
        when(stocktakeService.reverse("SC-202606-0001", "agent:5")).thenReturn(reversed);

        ToolResult result = tool.execute(Map.of("doc_no", "SC-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "SC-202606-0001");
        assertThat(result.data()).containsEntry("status", "REVERSED");
        assertThat(result.data()).containsKey("note");
        verify(stocktakeService).reverse("SC-202606-0001", "agent:5");
    }

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(stocktakeService);
    }

    @Test
    void 盘点单不存在_转fail() {
        when(stocktakeService.reverse(eq("SC-999999-0001"), any()))
                .thenThrow(new StockCountNotFoundException("SC-999999-0001"));
        ToolResult result = tool.execute(Map.of("doc_no", "SC-999999-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 非法状态_已冲销_转fail() {
        when(stocktakeService.reverse(eq("SC-202606-0001"), any()))
                .thenThrow(new IllegalStateException("盘点单[SC-202606-0001] 已冲销，不可重复冲销"));
        ToolResult result = tool.execute(Map.of("doc_no", "SC-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 非法流转_转fail() {
        when(stocktakeService.reverse(eq("SC-202606-0001"), any()))
                .thenThrow(new IllegalStateTransitionException("SC-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));
        ToolResult result = tool.execute(Map.of("doc_no", "SC-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(stocktakeService.reverse(eq("SC-202606-0001"), any()))
                .thenThrow(new IllegalArgumentException("原流水缺失或无单价"));
        ToolResult result = tool.execute(Map.of("doc_no", "SC-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }
}
