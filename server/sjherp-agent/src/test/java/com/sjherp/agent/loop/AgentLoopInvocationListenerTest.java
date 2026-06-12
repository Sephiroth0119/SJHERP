package com.sjherp.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.LlmUsage;
import com.sjherp.agent.llm.ToolCall;
import com.sjherp.agent.loop.AgentLoopTest.FakeCodec;
import com.sjherp.agent.loop.AgentLoopTest.FakeLlmClient;
import com.sjherp.agent.loop.AgentLoopTest.StubTool;
import com.sjherp.agent.tool.RequiredFieldsToolArgumentValidator;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;

/**
 * AgentInvocationListener 回调时机单元测试（M1-T06 验收）：假 listener 记录每次回调——
 * LLM 调用序号与 usage 透传 / 终轮单独 JSON 调用计入 / LLM 失败上报后原样抛出 /
 * 工具调用成功与失败口径 / 确认与取消恢复 / 结果摘要截断 / 回调异常不影响主流程。
 *
 * <p>测试替身复用 {@link AgentLoopTest} 的 FakeLlmClient / FakeCodec / StubTool。
 */
class AgentLoopInvocationListenerTest {

    // ---------------------------------------------------------------- 测试替身

    /** 记录每次回调入参的假 listener */
    static final class RecordingListener implements AgentInvocationListener {

        record LlmCallEvent(String sessionId, int round, String model, long durationMs,
                            Integer promptTokens, Integer completionTokens,
                            boolean hasToolCalls, String error) {
        }

        record ToolCallEvent(String sessionId, String toolName, String argumentsJson, boolean success,
                             String resultSummary, long durationMs, ToolRiskLevel riskLevel,
                             boolean confirmed) {
        }

        final List<LlmCallEvent> llmCalls = new ArrayList<>();
        final List<ToolCallEvent> toolCalls = new ArrayList<>();

        @Override
        public void onLlmCall(String sessionId, int round, String model, long durationMs,
                              Integer promptTokens, Integer completionTokens,
                              boolean hasToolCalls, String error) {
            llmCalls.add(new LlmCallEvent(sessionId, round, model, durationMs,
                    promptTokens, completionTokens, hasToolCalls, error));
        }

        @Override
        public void onToolCall(String sessionId, String toolName, String argumentsJson, boolean success,
                               String resultSummary, long durationMs, ToolRiskLevel riskLevel,
                               boolean confirmed) {
            toolCalls.add(new ToolCallEvent(sessionId, toolName, argumentsJson, success,
                    resultSummary, durationMs, riskLevel, confirmed));
        }
    }

    private final RecordingListener listener = new RecordingListener();

    private AgentLoop loop(FakeLlmClient client) {
        return new AgentLoop(client, new FakeCodec(),
                new RequiredFieldsToolArgumentValidator(), ToolPermissionChecker.allowAll(), listener);
    }

    private static AgentLoopRequest.Builder request(List<Tool> tools) {
        return AgentLoopRequest.builder()
                .systemPrompt("系统提示")
                .history(List.of(com.sjherp.agent.llm.LlmMessage.user("用户输入")))
                .tools(tools)
                .context(new com.sjherp.agent.tool.ToolContext("session-1", "user-1", "测试指令"))
                .finalJsonMode(FinalJsonMode.JSON_WITH_TOOLS);
    }

    private static LlmResponse toolCallResponse(String id, String tool, String argsJson,
                                                String model, LlmUsage usage) {
        return new LlmResponse(null, List.of(new ToolCall(id, tool, argsJson)), model, usage);
    }

    // ---------------------------------------------------------------- LLM 调用回调

    @Test
    void llmCallsReportedWithRoundModelUsageAndToolFlag() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "{\"message\":\"hi\"}",
                        "deepseek-chat", new LlmUsage(120, 30)),
                new LlmResponse("{\"text\":\"done\"}", List.of(), "deepseek-chat", new LlmUsage(180, 50)));
        loop(client).run(request(List.of(echo)).build());

        assertEquals(2, listener.llmCalls.size());
        RecordingListener.LlmCallEvent first = listener.llmCalls.get(0);
        assertEquals("session-1", first.sessionId());
        assertEquals(1, first.round());
        assertEquals("deepseek-chat", first.model());
        assertEquals(120, first.promptTokens());
        assertEquals(30, first.completionTokens());
        assertTrue(first.hasToolCalls());
        assertNull(first.error());
        assertTrue(first.durationMs() >= 0);

        RecordingListener.LlmCallEvent second = listener.llmCalls.get(1);
        assertEquals(2, second.round());
        assertEquals(180, second.promptTokens());
        assertFalse(second.hasToolCalls());
        assertNull(second.error());
    }

    @Test
    void usageMissingIsReportedAsNullTokens() {
        // 厂商未返回 usage（便捷构造 model/usage 均为 null）：token 上报为 null，不影响主流程
        FakeLlmClient client = new FakeLlmClient(new LlmResponse("{\"text\":\"你好\"}"));
        loop(client).run(request(List.of()).build());

        assertEquals(1, listener.llmCalls.size());
        RecordingListener.LlmCallEvent event = listener.llmCalls.get(0);
        assertNull(event.model());
        assertNull(event.promptTokens());
        assertNull(event.completionTokens());
        assertNull(event.error());
    }

    @Test
    void separateFinalCallIsReportedAsExtraRound() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                new LlmResponse("自由文本（应被丢弃）", List.of(), "deepseek-chat", new LlmUsage(100, 20)),
                new LlmResponse("{\"text\":\"final\"}", List.of(), "deepseek-chat", new LlmUsage(110, 40)));
        loop(client).run(request(List.of(echo))
                .finalJsonMode(FinalJsonMode.JSON_SEPARATE_FINAL_CALL)
                .build());

        // 终轮单独 JSON 调用也计入观测（round 连续递增）
        assertEquals(2, listener.llmCalls.size());
        assertEquals(1, listener.llmCalls.get(0).round());
        assertEquals(2, listener.llmCalls.get(1).round());
        assertEquals(40, listener.llmCalls.get(1).completionTokens());
    }

    @Test
    void maxIterationsForcedFinalCallIsReported() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "{\"message\":\"1\"}", "m", new LlmUsage(10, 1)),
                toolCallResponse("c2", "echo", "{\"message\":\"2\"}", "m", new LlmUsage(20, 2)),
                new LlmResponse("{\"text\":\"forced\"}", List.of(), "m", new LlmUsage(30, 3)));
        loop(client).run(request(List.of(echo)).maxIterations(2).build());

        // 强制收尾调用同样上报：3 次 LLM 调用 + 2 次工具调用
        assertEquals(3, listener.llmCalls.size());
        assertEquals(3, listener.llmCalls.get(2).round());
        assertFalse(listener.llmCalls.get(2).hasToolCalls());
        assertEquals(2, listener.toolCalls.size());
    }

    @Test
    void llmFailureIsReportedWithErrorThenRethrown() {
        // 脚本用尽即抛 IllegalStateException：模拟 LLM 调用失败
        FakeLlmClient client = new FakeLlmClient();
        AgentLoopRequest req = request(List.of()).build();
        assertThrows(IllegalStateException.class, () -> loop(client).run(req));

        assertEquals(1, listener.llmCalls.size());
        RecordingListener.LlmCallEvent event = listener.llmCalls.get(0);
        assertNull(event.model());
        assertNull(event.promptTokens());
        assertTrue(event.error().contains("脚本响应用尽"));
    }

    // ---------------------------------------------------------------- 工具调用回调

    @Test
    void toolCallReportedWithRiskLevelAndResultSummary() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "{\"message\":\"hi\"}", null, null),
                new LlmResponse("{\"text\":\"done\"}"));
        loop(client).run(request(List.of(echo)).build());

        assertEquals(1, listener.toolCalls.size());
        RecordingListener.ToolCallEvent event = listener.toolCalls.get(0);
        assertEquals("session-1", event.sessionId());
        assertEquals("echo", event.toolName());
        assertEquals("{\"message\":\"hi\"}", event.argumentsJson());
        assertTrue(event.success());
        assertTrue(event.resultSummary().startsWith("{\"success\":true"));
        assertEquals(ToolRiskLevel.NORMAL, event.riskLevel());
        assertFalse(event.confirmed());
        assertTrue(event.durationMs() >= 0);
    }

    @Test
    void unknownToolReportedWithNullRiskLevel() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "ghost", "{}", null, null),
                new LlmResponse("{\"text\":\"ok\"}"));
        loop(client).run(request(List.of(StubTool.normal("echo"))).build());

        RecordingListener.ToolCallEvent event = listener.toolCalls.get(0);
        assertEquals("ghost", event.toolName());
        assertFalse(event.success());
        assertNull(event.riskLevel());
        assertTrue(event.resultSummary().contains("未知工具"));
    }

    @Test
    void longToolResultSummaryIsTruncated() {
        String longText = "x".repeat(600);
        StubTool verbose = new StubTool("verbose", ToolRiskLevel.NORMAL, null, null,
                args -> ToolResult.ok(Map.of("payload", longText)));
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "verbose", "{}", null, null),
                new LlmResponse("{\"text\":\"ok\"}"));
        loop(client).run(request(List.of(verbose)).build());

        RecordingListener.ToolCallEvent event = listener.toolCalls.get(0);
        assertTrue(event.resultSummary().endsWith("...(已截断)"));
        // 截断阈值 500（见 AgentLoop.RESULT_SUMMARY_MAX_LENGTH）+ 后缀
        assertEquals(500 + "...(已截断)".length(), event.resultSummary().length());
    }

    // ---------------------------------------------------------------- 高风险确认与取消

    @Test
    void interceptedHighRiskCallIsNotReportedUntilResumed() {
        StubTool post = StubTool.high("demo_post_document");
        FakeLlmClient first = new FakeLlmClient(
                toolCallResponse("c1", "demo_post_document", "{\"documentId\":\"DOC-1\"}", null, null));
        AgentLoopResult intercepted = loop(first).run(request(List.of(post)).build());

        // 拦截即中断：工具未执行，不上报 onToolCall（与 ToolCallRecord 口径一致）
        assertTrue(intercepted.isPendingConfirmation());
        assertTrue(listener.toolCalls.isEmpty());
        assertEquals(1, listener.llmCalls.size());

        // 确认恢复：执行后上报 confirmed=true
        FakeLlmClient second = new FakeLlmClient(new LlmResponse("{\"text\":\"已过账\"}"));
        loop(second).resume(request(List.of(post)).build(), intercepted.pendingToolCall(), true);

        assertEquals(1, listener.toolCalls.size());
        RecordingListener.ToolCallEvent event = listener.toolCalls.get(0);
        assertEquals("demo_post_document", event.toolName());
        assertTrue(event.success());
        assertEquals(ToolRiskLevel.HIGH, event.riskLevel());
        assertTrue(event.confirmed());
    }

    @Test
    void cancelledHighRiskCallReportedAsFailureWithZeroDuration() {
        StubTool post = StubTool.high("demo_post_document");
        FakeLlmClient first = new FakeLlmClient(
                toolCallResponse("c1", "demo_post_document", "{\"documentId\":\"DOC-1\"}", null, null));
        PendingToolCall pending = loop(first).run(request(List.of(post)).build()).pendingToolCall();

        FakeLlmClient second = new FakeLlmClient(new LlmResponse("{\"text\":\"好的，已取消\"}"));
        loop(second).resume(request(List.of(post)).build(), pending, false);

        assertEquals(1, listener.toolCalls.size());
        RecordingListener.ToolCallEvent event = listener.toolCalls.get(0);
        assertFalse(event.success());
        assertEquals(0, event.durationMs());
        assertFalse(event.confirmed());
        assertEquals(ToolRiskLevel.HIGH, event.riskLevel());
        assertTrue(event.resultSummary().contains("用户已取消"));
        assertEquals(0, post.executions);
    }

    // ---------------------------------------------------------------- 回调兜底

    @Test
    void listenerExceptionsNeverBreakTheLoop() {
        AgentInvocationListener broken = new AgentInvocationListener() {
            @Override
            public void onLlmCall(String sessionId, int round, String model, long durationMs,
                                  Integer promptTokens, Integer completionTokens,
                                  boolean hasToolCalls, String error) {
                throw new IllegalStateException("观测崩了");
            }

            @Override
            public void onToolCall(String sessionId, String toolName, String argumentsJson,
                                   boolean success, String resultSummary, long durationMs,
                                   ToolRiskLevel riskLevel, boolean confirmed) {
                throw new IllegalStateException("观测崩了");
            }
        };
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "{\"message\":\"hi\"}", null, null),
                new LlmResponse("{\"text\":\"done\"}"));
        AgentLoop loop = new AgentLoop(client, new FakeCodec(),
                new RequiredFieldsToolArgumentValidator(), ToolPermissionChecker.allowAll(), broken);

        AgentLoopResult result = loop.run(request(List.of(echo)).build());
        // 回调异常被吞掉：循环正常完成、工具正常执行
        assertEquals("{\"text\":\"done\"}", result.finalText());
        assertEquals(1, echo.executions);
    }
}
