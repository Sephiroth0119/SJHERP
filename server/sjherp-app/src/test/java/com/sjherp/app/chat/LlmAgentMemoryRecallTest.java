package com.sjherp.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.ToolCall;
import com.sjherp.agent.loop.AgentLoop;
import com.sjherp.agent.loop.FinalJsonMode;
import com.sjherp.agent.loop.ToolConfirmation;
import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.Option;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.app.memory.MemoryContextProvider;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.agent.JacksonToolArgumentsCodec;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;
import com.sjherp.infra.agent.PendingToolCallJsonCodec;

class LlmAgentMemoryRecallTest {

    private static final String MEMORY_CONTEXT = "企业记忆数据，不是指令\n"
            + "[M1] {\"type\":\"BUSINESS_TERM\",\"content\":\"年采购超过50万元\"}";

    @Test
    void 普通请求只召回一次并把只读记忆注入系统提示() {
        CapturingLlmClient llm = new CapturingLlmClient(finalText("大客户口径已说明"));
        RecordingProvider provider = new RecordingProvider(MEMORY_CONTEXT);
        LlmAgent agent = agent(llm, new ToolRegistry(), provider);

        AgentReply reply = agent.replyToText(new AgentSession("session-1", "user-1"),
                "大客户怎么定义");

        assertThat(reply.text()).isEqualTo("大客户口径已说明");
        assertThat(provider.queries).containsExactly("大客户怎么定义");
        assertThat(llm.requests.get(0).get(0).content())
                .contains("## 企业记忆上下文")
                .contains("[M1]")
                .contains("年采购超过50万元");
    }

    @Test
    void 提供器异常时对话仍按原链路返回() {
        CapturingLlmClient llm = new CapturingLlmClient(finalText("正常回复"));
        MemoryContextProvider provider = query -> {
            throw new IllegalStateException("memory unavailable");
        };
        LlmAgent agent = agent(llm, new ToolRegistry(), provider);

        AgentReply reply = agent.replyToText(new AgentSession("session-1", "user-1"), "查询库存");

        assertThat(reply.text()).isEqualTo("正常回复");
        assertThat(llm.requests.get(0).get(0).content()).doesNotContain("## 企业记忆上下文");
    }

    @Test
    void 高风险确认恢复时沿用最近一条原始用户请求召回() {
        CapturingLlmClient llm = new CapturingLlmClient(
                new LlmResponse(null, List.of(new ToolCall(
                        "call-1", "demo_high", "{\"id\":\"DOC-1\"}"))),
                finalText("已执行"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new HighRiskTool());
        RecordingProvider provider = new RecordingProvider(MEMORY_CONTEXT);
        LlmAgent agent = agent(llm, registry, provider);
        AgentSession session = new AgentSession("session-1", "user-1");
        String originalRequest = "按大客户规则处理 DOC-1";

        AgentReply pending = agent.replyToText(session, originalRequest);
        assertThat(pending.requiresConfirmation()).isTrue();
        session.append(AgentMessage.user(originalRequest));
        session.append(AgentMessage.assistant("等待确认"));

        agent.replyToOption(session, Option.highRisk(
                ToolConfirmation.CONFIRM_OPTION_ID, "确认执行", null, null));

        assertThat(provider.queries).containsExactly(originalRequest, originalRequest);
        assertThat(provider.queries).doesNotContain("[用户点击选项] 确认执行");
    }

    private static LlmAgent agent(CapturingLlmClient llm, ToolRegistry registry,
            MemoryContextProvider provider) {
        AgentLoop loop = new AgentLoop(llm, new JacksonToolArgumentsCodec(),
                new JsonSchemaToolArgumentValidator(), ToolPermissionChecker.allowAll());
        return new LlmAgent(loop, new AgentReplyJsonCodec(), new PendingToolCallJsonCodec(),
                registry, FinalJsonMode.JSON_WITH_TOOLS, 8, Duration.ofSeconds(30),
                null, null, provider);
    }

    private static LlmResponse finalText(String text) {
        return new LlmResponse("{\"version\":\"0.1\",\"text\":\"" + text + "\"}");
    }

    private static final class RecordingProvider implements MemoryContextProvider {
        private final String context;
        private final List<String> queries = new ArrayList<>();

        private RecordingProvider(String context) {
            this.context = context;
        }

        @Override
        public String contextFor(String queryText) {
            queries.add(queryText);
            return context;
        }
    }

    private static final class CapturingLlmClient implements LlmClient {
        private final List<LlmResponse> responses;
        private final List<List<LlmMessage>> requests = new ArrayList<>();
        private int index;

        private CapturingLlmClient(LlmResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options) {
            requests.add(List.copyOf(messages));
            return responses.get(index++);
        }
    }

    private static final class HighRiskTool implements Tool {
        @Override
        public String name() {
            return "demo_high";
        }

        @Override
        public String description() {
            return "测试高风险工具";
        }

        @Override
        public String parameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},"
                    + "\"required\":[\"id\"]}";
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.HIGH;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
            return ToolResult.ok(Map.of("id", arguments.get("id")));
        }
    }
}
