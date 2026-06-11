package com.sjherp.infra.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.ToolCall;
import com.sjherp.agent.loop.PendingToolCall;

/**
 * PendingToolCallJsonCodec 单元测试：高风险拦截现场的持久化往返
 * （agent_session.pending_tool_call 列，杀进程后确认流程可恢复）。
 */
class PendingToolCallJsonCodecTest {

    private final PendingToolCallJsonCodec codec = new PendingToolCallJsonCodec();

    @Test
    void roundTripPreservesFullState() {
        PendingToolCall pending = new PendingToolCall(
                "我将为你过账",
                List.of(
                        new ToolCall("c1", "echo", "{\"message\":\"hi\"}"),
                        new ToolCall("c2", "demo_post_document", "{\"documentId\":\"DOC-1\"}")),
                List.of(new PendingToolCall.ExecutedResult("c1", "{\"success\":true,\"data\":{\"echo\":\"hi\"}}")),
                "c2",
                "即将执行高风险操作「demo_post_document」");

        PendingToolCall restored = codec.fromJson(codec.toJson(pending));

        assertThat(restored.assistantContent()).isEqualTo("我将为你过账");
        assertThat(restored.toolCalls()).hasSize(2);
        assertThat(restored.toolCalls().get(1).argumentsJson()).isEqualTo("{\"documentId\":\"DOC-1\"}");
        assertThat(restored.executedResults()).hasSize(1);
        assertThat(restored.executedResults().get(0).toolCallId()).isEqualTo("c1");
        assertThat(restored.pendingToolCallId()).isEqualTo("c2");
        assertThat(restored.summary()).contains("demo_post_document");
        assertThat(restored.toolName()).isEqualTo("demo_post_document");
    }

    @Test
    void roundTripWithNullAssistantContent() {
        PendingToolCall pending = new PendingToolCall(
                null,
                List.of(new ToolCall("c1", "demo_post_document", "{}")),
                List.of(), "c1", "摘要");
        PendingToolCall restored = codec.fromJson(codec.toJson(pending));
        assertThat(restored.assistantContent()).isNull();
        assertThat(restored.executedResults()).isEmpty();
    }

    @Test
    void invalidJsonThrowsIllegalState() {
        assertThatThrownBy(() -> codec.fromJson("not-json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
