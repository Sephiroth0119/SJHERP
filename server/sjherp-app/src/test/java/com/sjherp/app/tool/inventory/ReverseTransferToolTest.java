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
import com.sjherp.app.transfer.TransferAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferNotFoundException;

/**
 * 冲销调拨单工具单测（M4-T07c，HIGH HITL）：name/riskLevel=HIGH/requiredPermission=inventory:transfer/
 * parameterSchema、execute 透传、返回数据、异常映射 fail（NotFound/IllegalState(Transition)/IllegalArgument）。
 * 调拨不出 GL 凭证，故无 PeriodClosed 分支。
 */
class ReverseTransferToolTest {

    private TransferAppService appService;
    private ReverseTransferTool tool;
    private final ToolContext context = new ToolContext("session-tr", "5", "冲销调拨单");

    @BeforeEach
    void setUp() {
        appService = mock(TransferAppService.class);
        tool = new ReverseTransferTool(appService);
    }

    @Test
    void name() {
        assertThat(tool.name()).isEqualTo("reverse_transfer");
    }

    @Test
    void 风险级别HIGH_权限点inventory_transfer() {
        assertThat(tool.riskLevel()).isEqualTo(ToolRiskLevel.HIGH);
        assertThat(tool.requiredPermission()).isEqualTo("inventory:transfer");
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
        TransferDocument reversed = mock(TransferDocument.class);
        when(reversed.getDocNo()).thenReturn("TR-202606-0001");
        when(reversed.getStatus()).thenReturn(DocumentStatus.REVERSED);
        when(appService.reverse("TR-202606-0001", "agent:5")).thenReturn(reversed);

        ToolResult result = tool.execute(Map.of("doc_no", "TR-202606-0001"), context);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("docNo", "TR-202606-0001");
        assertThat(result.data()).containsEntry("status", "REVERSED");
        assertThat(result.data()).containsKey("note");
        verify(appService).reverse("TR-202606-0001", "agent:5");
    }

    @Test
    void doc_no缺失_失败_不调服务() {
        ToolResult result = tool.execute(Map.of(), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("doc_no");
        verifyNoInteractions(appService);
    }

    @Test
    void 调拨单不存在_转fail() {
        when(appService.reverse(eq("TR-999999-0001"), any()))
                .thenThrow(new TransferNotFoundException("TR-999999-0001"));
        ToolResult result = tool.execute(Map.of("doc_no", "TR-999999-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("不存在");
    }

    @Test
    void 非法状态_已冲销_转fail() {
        when(appService.reverse(eq("TR-202606-0001"), any()))
                .thenThrow(new IllegalStateException("调拨单[TR-202606-0001] 已冲销，不可重复冲销"));
        ToolResult result = tool.execute(Map.of("doc_no", "TR-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 非法流转_转fail() {
        when(appService.reverse(eq("TR-202606-0001"), any()))
                .thenThrow(new IllegalStateTransitionException("TR-202606-0001",
                        DocumentStatus.DRAFT, DocumentStatus.REVERSED));
        ToolResult result = tool.execute(Map.of("doc_no", "TR-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }

    @Test
    void 参数非法_转fail() {
        when(appService.reverse(eq("TR-202606-0001"), any()))
                .thenThrow(new IllegalArgumentException("原流水缺失或无单价"));
        ToolResult result = tool.execute(Map.of("doc_no", "TR-202606-0001"), context);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("冲销被拒绝");
    }
}
