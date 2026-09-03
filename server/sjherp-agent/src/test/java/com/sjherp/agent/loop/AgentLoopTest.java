package com.sjherp.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.ToolCall;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.tool.RequiredFieldsToolArgumentValidator;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolArgumentsCodec;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;

/**
 * AgentLoop 单元测试（M1-T02/T03 验收）：假 LlmClient + 假 Tool 驱动——
 * 正常工具往返 / 未知工具 / 异常回灌 / 参数校验失败 / 权限不足 / 最大迭代 /
 * 超时预算 / 终轮 JSON 模式 / 高风险拦截 / 确认恢复 / 取消恢复。
 */
class AgentLoopTest {

    private static final ToolContext CONTEXT = new ToolContext("session-1", "user-1", "测试指令");

    // ---------------------------------------------------------------- 测试替身

    /** 按脚本逐次返回响应的假 LLM，记录每次调用的消息与参数 */
    static final class FakeLlmClient implements LlmClient {

        final List<LlmResponse> scripted = new ArrayList<>();
        final List<List<LlmMessage>> capturedMessages = new ArrayList<>();
        final List<LlmRequestOptions> capturedOptions = new ArrayList<>();
        private int index;

        FakeLlmClient(LlmResponse... responses) {
            scripted.addAll(List.of(responses));
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options) {
            capturedMessages.add(List.copyOf(messages));
            capturedOptions.add(options);
            if (index >= scripted.size()) {
                throw new IllegalStateException("脚本响应用尽（第 " + (index + 1) + " 次调用）");
            }
            return scripted.get(index++);
        }

        int callCount() {
            return capturedMessages.size();
        }
    }

    /** 极简 JSON 编解码（仅支持测试用的扁平字符串键值对；serialize 保持插入顺序） */
    static final class FakeCodec implements ToolArgumentsCodec {

        private static final Pattern STRING_PAIR = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");

        @Override
        public Map<String, Object> parse(String argumentsJson) {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                return Map.of();
            }
            String trimmed = argumentsJson.strip();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw new IllegalArgumentException("不是 JSON 对象: " + argumentsJson);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            Matcher matcher = STRING_PAIR.matcher(trimmed);
            while (matcher.find()) {
                result.put(matcher.group(1), matcher.group(2));
            }
            return result;
        }

        @Override
        public String serialize(Map<String, Object> data) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(entry.getKey()).append("\":").append(valueJson(entry.getValue()));
            }
            return sb.append('}').toString();
        }

        private String valueJson(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof Boolean || value instanceof Number) {
                return value.toString();
            }
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return serialize(typed);
            }
            return '"' + value.toString().replace("\"", "\\\"") + '"';
        }
    }

    /** 行为可定制的假工具，记录执行次数与最近一次参数 */
    static final class StubTool implements Tool {

        private final String name;
        private final ToolRiskLevel riskLevel;
        private final String requiredPermission;
        private final String schema;
        private final Function<Map<String, Object>, ToolResult> behavior;

        int executions;
        Map<String, Object> lastArguments;
        ToolContext lastContext;

        StubTool(String name, ToolRiskLevel riskLevel, String requiredPermission, String schema,
                 Function<Map<String, Object>, ToolResult> behavior) {
            this.name = name;
            this.riskLevel = riskLevel;
            this.requiredPermission = requiredPermission;
            this.schema = schema;
            this.behavior = behavior;
        }

        static StubTool normal(String name) {
            return new StubTool(name, ToolRiskLevel.NORMAL, null, null,
                    args -> ToolResult.ok(Map.of("echo", String.valueOf(args.get("message")))));
        }

        static StubTool high(String name) {
            return new StubTool(name, ToolRiskLevel.HIGH, "demo:post", null,
                    args -> ToolResult.ok(Map.of("status", "POSTED")));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试工具 " + name;
        }

        @Override
        public String parameterSchema() {
            return schema;
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return riskLevel;
        }

        @Override
        public String requiredPermission() {
            return requiredPermission;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
            executions++;
            lastArguments = arguments;
            lastContext = context;
            return behavior.apply(arguments);
        }
    }

    private static AgentLoop loop(LlmClient client) {
        return new AgentLoop(client, new FakeCodec(),
                new RequiredFieldsToolArgumentValidator(), ToolPermissionChecker.allowAll());
    }

    private static AgentLoopRequest.Builder request(List<Tool> tools) {
        return AgentLoopRequest.builder()
                .systemPrompt("系统提示")
                .history(List.of(LlmMessage.user("用户输入")))
                .tools(tools)
                .context(CONTEXT)
                .finalJsonMode(FinalJsonMode.JSON_WITH_TOOLS);
    }

    private static LlmResponse toolCallResponse(String id, String tool, String argsJson) {
        return new LlmResponse(null, List.of(new ToolCall(id, tool, argsJson)));
    }

    // ---------------------------------------------------------------- 退化与正常往返

    @Test
    void emptyToolsDegradesToSingleCall() {
        FakeLlmClient client = new FakeLlmClient(new LlmResponse("{\"text\":\"你好\"}"));
        AgentLoopResult result = loop(client).run(request(List.of()).build());

        assertEquals("{\"text\":\"你好\"}", result.finalText());
        assertFalse(result.isPendingConfirmation());
        assertTrue(result.toolCallRecords().isEmpty());
        assertEquals(1, client.callCount());
        // 无工具时直接按终轮参数调用：不带 tools、要求 json_object
        assertFalse(client.capturedOptions.get(0).hasTools());
        assertTrue(client.capturedOptions.get(0).jsonResponseFormat());
    }

    @Test
    void normalToolRoundTrip() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "{\"message\":\"hi\"}"),
                new LlmResponse("{\"text\":\"done\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(echo)).build());

        // 工具被执行且参数已解析
        assertEquals(1, echo.executions);
        assertEquals("hi", echo.lastArguments.get("message"));
        assertEquals(CONTEXT, echo.lastContext);

        // 第二次调用的上下文应包含 assistant 工具调用消息与 TOOL 结果回灌
        List<LlmMessage> second = client.capturedMessages.get(1);
        LlmMessage assistant = second.get(second.size() - 2);
        LlmMessage tool = second.get(second.size() - 1);
        assertEquals(MessageRole.ASSISTANT, assistant.role());
        assertTrue(assistant.hasToolCalls());
        assertEquals(MessageRole.TOOL, tool.role());
        assertEquals("c1", tool.toolCallId());
        assertTrue(tool.content().startsWith("{\"success\":true"));
        assertTrue(tool.content().contains("\"echo\":\"hi\""));

        // 调用记录：名称 / 参数 / 结果 / 耗时
        assertEquals(1, result.toolCallRecords().size());
        ToolCallRecord record = result.toolCallRecords().get(0);
        assertEquals("echo", record.toolName());
        assertEquals("{\"message\":\"hi\"}", record.argumentsJson());
        assertTrue(record.success());
        assertTrue(record.elapsedMillis() >= 0);

        assertEquals("{\"text\":\"done\"}", result.finalText());
    }

    @Test
    void businessFailureResultIsFedBackAsUnsuccessful() {
        StubTool reject = new StubTool("reject", ToolRiskLevel.NORMAL, null, null,
                args -> ToolResult.fail("库存不足，宁可拒绝不可破坏模型"));
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "reject", "{}"),
                new LlmResponse("{\"text\":\"ok\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(reject)).build());

        ToolCallRecord record = result.toolCallRecords().get(0);
        assertFalse(record.success());
        assertTrue(record.resultContent().contains("库存不足"));
        assertEquals("{\"text\":\"ok\"}", result.finalText());
    }

    // ---------------------------------------------------------------- 防护：错误回灌不中断

    @Test
    void unknownToolIsFedBackAsErrorWithoutAborting() {
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "ghost", "{}"),
                new LlmResponse("{\"text\":\"ok\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(StubTool.normal("echo"))).build());

        ToolCallRecord record = result.toolCallRecords().get(0);
        assertFalse(record.success());
        assertTrue(record.resultContent().contains("未知工具"));
        // 错误以 TOOL 消息回灌，循环继续直至最终文本
        List<LlmMessage> second = client.capturedMessages.get(1);
        assertEquals(MessageRole.TOOL, second.get(second.size() - 1).role());
        assertTrue(second.get(second.size() - 1).content().contains("未知工具"));
        assertEquals("{\"text\":\"ok\"}", result.finalText());
    }

    @Test
    void toolExceptionIsFedBackWithoutAborting() {
        StubTool broken = new StubTool("broken", ToolRiskLevel.NORMAL, null, null,
                args -> {
                    throw new IllegalStateException("boom");
                });
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "broken", "{}"),
                new LlmResponse("{\"text\":\"ok\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(broken)).build());

        ToolCallRecord record = result.toolCallRecords().get(0);
        assertFalse(record.success());
        assertTrue(record.resultContent().contains("工具执行异常"));
        assertTrue(record.resultContent().contains("boom"));
        assertEquals("{\"text\":\"ok\"}", result.finalText());
    }

    @Test
    void invalidArgumentsJsonIsFedBack() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "not-json"),
                new LlmResponse("{\"text\":\"ok\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(echo)).build());

        assertEquals(0, echo.executions);
        assertTrue(result.toolCallRecords().get(0).resultContent().contains("不是合法 JSON"));
        assertEquals("{\"text\":\"ok\"}", result.finalText());
    }

    @Test
    void validationFailureIsFedBackAndToolNotExecuted() {
        StubTool echo = new StubTool("echo", ToolRiskLevel.NORMAL, null,
                "{\"type\":\"object\",\"required\":[\"message\"]}",
                args -> ToolResult.ok(Map.of()));
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "{}"),
                new LlmResponse("{\"text\":\"ok\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(echo)).build());

        assertEquals(0, echo.executions);
        ToolCallRecord record = result.toolCallRecords().get(0);
        assertFalse(record.success());
        assertTrue(record.resultContent().contains("参数校验失败"));
        assertTrue(record.resultContent().contains("message"));
    }

    @Test
    void permissionDeniedIsFedBackAndToolNotExecuted() {
        StubTool guarded = new StubTool("guarded", ToolRiskLevel.NORMAL, "perm:x", null,
                args -> ToolResult.ok(Map.of()));
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "guarded", "{}"),
                new LlmResponse("{\"text\":\"ok\"}"));
        AgentLoop loop = new AgentLoop(client, new FakeCodec(),
                new RequiredFieldsToolArgumentValidator(), (tool, context) -> false);
        AgentLoopResult result = loop.run(request(List.of(guarded)).build());

        assertEquals(0, guarded.executions);
        assertTrue(result.toolCallRecords().get(0).resultContent().contains("权限不足"));
        assertEquals("{\"text\":\"ok\"}", result.finalText());
    }

    // ---------------------------------------------------------------- 防护：迭代与超时预算

    @Test
    void maxIterationsForcesFinalCallWithoutTools() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "echo", "{\"message\":\"1\"}"),
                toolCallResponse("c2", "echo", "{\"message\":\"2\"}"),
                new LlmResponse("{\"text\":\"forced\"}"));
        AgentLoopResult result = loop(client)
                .run(request(List.of(echo)).maxIterations(2).build());

        assertEquals(2, echo.executions);
        assertEquals(3, client.callCount());
        // 第三次为强制终轮：不带工具、要求 json_object
        LlmRequestOptions forced = client.capturedOptions.get(2);
        assertFalse(forced.hasTools());
        assertTrue(forced.jsonResponseFormat());
        assertEquals("{\"text\":\"forced\"}", result.finalText());
    }

    @Test
    void timeoutBudgetExceededThrows() {
        FakeLlmClient client = new FakeLlmClient(new LlmResponse("{\"text\":\"x\"}"));
        AgentLoopRequest req = request(List.of(StubTool.normal("echo")))
                .timeout(Duration.ZERO)
                .build();
        assertThrows(AgentLoopTimeoutException.class, () -> loop(client).run(req));
        assertEquals(0, client.callCount());
    }

    // ---------------------------------------------------------------- 终轮 JSON 模式

    @Test
    void separateFinalCallModeMakesExtraJsonCall() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                new LlmResponse("自由文本（应被丢弃）"),
                new LlmResponse("{\"text\":\"final\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(echo))
                .finalJsonMode(FinalJsonMode.JSON_SEPARATE_FINAL_CALL)
                .build());

        assertEquals(2, client.callCount());
        // 工具轮：带 tools、不带 json_object（DeepSeek 实测两者互斥）
        assertTrue(client.capturedOptions.get(0).hasTools());
        assertFalse(client.capturedOptions.get(0).jsonResponseFormat());
        // 终轮：不带 tools、要求 json_object
        assertFalse(client.capturedOptions.get(1).hasTools());
        assertTrue(client.capturedOptions.get(1).jsonResponseFormat());
        assertEquals("{\"text\":\"final\"}", result.finalText());
    }

    @Test
    void separateFinalCallBlankFallsBackToFreeText() {
        // 回归：终轮 json_object 偶发返回空内容时，应退回本轮自由文本，
        // 而非把空串抛给上层（否则用户只会看到「模型未返回内容，请重试」死胡同）。
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(
                new LlmResponse("请提供单价以便创建采购单"),
                new LlmResponse("   "));
        AgentLoopResult result = loop(client).run(request(List.of(echo))
                .finalJsonMode(FinalJsonMode.JSON_SEPARATE_FINAL_CALL)
                .build());

        assertEquals(2, client.callCount());
        assertEquals("请提供单价以便创建采购单", result.finalText());
        assertFalse(result.isPendingConfirmation());
    }

    @Test
    void withToolsModeRequestsJsonOnToolRounds() {
        StubTool echo = StubTool.normal("echo");
        FakeLlmClient client = new FakeLlmClient(new LlmResponse("{\"text\":\"final\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(echo))
                .finalJsonMode(FinalJsonMode.JSON_WITH_TOOLS)
                .build());

        assertEquals(1, client.callCount());
        assertTrue(client.capturedOptions.get(0).hasTools());
        assertTrue(client.capturedOptions.get(0).jsonResponseFormat());
        assertEquals("{\"text\":\"final\"}", result.finalText());
    }

    // ---------------------------------------------------------------- 高风险拦截与恢复

    @Test
    void highRiskToolIsInterceptedWithoutExecution() {
        StubTool post = StubTool.high("demo_post_document");
        FakeLlmClient client = new FakeLlmClient(
                toolCallResponse("c1", "demo_post_document", "{\"documentId\":\"DOC-1\"}"));
        AgentLoopResult result = loop(client).run(request(List.of(post)).build());

        assertTrue(result.isPendingConfirmation());
        assertNull(result.finalText());
        assertEquals(0, post.executions);
        PendingToolCall pending = result.pendingToolCall();
        assertEquals("demo_post_document", pending.toolName());
        assertEquals("{\"documentId\":\"DOC-1\"}", pending.argumentsJson());
        assertEquals("c1", pending.pendingToolCallId());
        assertTrue(pending.summary().contains("demo_post_document"));
        // 拦截即中断：该调用未执行，不产生调用记录
        assertTrue(result.toolCallRecords().isEmpty());
    }

    @Test
    void resumeConfirmedExecutesPendingCallAndContinues() {
        StubTool post = StubTool.high("demo_post_document");
        FakeLlmClient first = new FakeLlmClient(
                toolCallResponse("c1", "demo_post_document", "{\"documentId\":\"DOC-1\"}"));
        PendingToolCall pending = loop(first).run(request(List.of(post)).build()).pendingToolCall();

        FakeLlmClient second = new FakeLlmClient(new LlmResponse("{\"text\":\"已过账\"}"));
        AgentLoopResult result = loop(second).resume(request(List.of(post)).build(), pending, true);

        assertEquals(1, post.executions);
        assertEquals("DOC-1", post.lastArguments.get("documentId"));
        assertFalse(result.isPendingConfirmation());
        assertEquals("{\"text\":\"已过账\"}", result.finalText());

        // 恢复调用的上下文应重建：assistant 工具调用消息 + 成功结果 TOOL 回灌
        List<LlmMessage> messages = second.capturedMessages.get(0);
        LlmMessage assistant = messages.get(messages.size() - 2);
        LlmMessage tool = messages.get(messages.size() - 1);
        assertTrue(assistant.hasToolCalls());
        assertEquals("c1", tool.toolCallId());
        assertTrue(tool.content().startsWith("{\"success\":true"));

        ToolCallRecord record = result.toolCallRecords().get(0);
        assertEquals("demo_post_document", record.toolName());
        assertTrue(record.success());
    }

    @Test
    void resumeCancelledFeedsCancellationWithoutExecuting() {
        StubTool post = StubTool.high("demo_post_document");
        FakeLlmClient first = new FakeLlmClient(
                toolCallResponse("c1", "demo_post_document", "{\"documentId\":\"DOC-1\"}"));
        PendingToolCall pending = loop(first).run(request(List.of(post)).build()).pendingToolCall();

        FakeLlmClient second = new FakeLlmClient(new LlmResponse("{\"text\":\"好的，已取消\"}"));
        AgentLoopResult result = loop(second).resume(request(List.of(post)).build(), pending, false);

        assertEquals(0, post.executions);
        assertEquals("{\"text\":\"好的，已取消\"}", result.finalText());

        // 取消语义以 TOOL 消息回灌（每个 tool_call_id 必须有应答）
        List<LlmMessage> messages = second.capturedMessages.get(0);
        LlmMessage tool = messages.get(messages.size() - 1);
        assertEquals("c1", tool.toolCallId());
        assertTrue(tool.content().contains("用户已取消"));

        ToolCallRecord record = result.toolCallRecords().get(0);
        assertFalse(record.success());
    }

    @Test
    void mixedBatchExecutesNormalCallsBeforeIntercepting() {
        StubTool echo = StubTool.normal("echo");
        StubTool post = StubTool.high("demo_post_document");
        FakeLlmClient first = new FakeLlmClient(new LlmResponse(null, List.of(
                new ToolCall("c1", "echo", "{\"message\":\"hi\"}"),
                new ToolCall("c2", "demo_post_document", "{\"documentId\":\"DOC-1\"}"))));
        AgentLoopResult intercepted = loop(first).run(request(List.of(echo, post)).build());

        // 普通调用先执行；高风险调用被拦截，已执行结果随现场保存
        assertEquals(1, echo.executions);
        assertEquals(0, post.executions);
        assertTrue(intercepted.isPendingConfirmation());
        PendingToolCall pending = intercepted.pendingToolCall();
        assertEquals("c2", pending.pendingToolCallId());
        assertEquals(1, pending.executedResults().size());
        assertEquals("c1", pending.executedResults().get(0).toolCallId());

        // 确认后：恢复执行高风险调用，已执行结果原样回灌、不重复执行
        FakeLlmClient second = new FakeLlmClient(new LlmResponse("{\"text\":\"done\"}"));
        AgentLoopResult result = loop(second).resume(
                request(List.of(echo, post)).build(), pending, true);
        assertEquals(1, echo.executions);
        assertEquals(1, post.executions);
        assertEquals("{\"text\":\"done\"}", result.finalText());

        List<LlmMessage> messages = second.capturedMessages.get(0);
        // 末三条：assistant(toolCalls) + tool(c1 已执行结果) + tool(c2 新执行结果)
        assertEquals("c1", messages.get(messages.size() - 2).toolCallId());
        assertEquals("c2", messages.get(messages.size() - 1).toolCallId());
        assertNotNull(messages.get(messages.size() - 3).toolCalls());
        assertEquals(2, messages.get(messages.size() - 3).toolCalls().size());
    }
}
