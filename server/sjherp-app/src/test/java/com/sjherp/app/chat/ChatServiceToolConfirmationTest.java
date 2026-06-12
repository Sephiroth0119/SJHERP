package com.sjherp.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.InMemoryAgentSessionRepository;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.agent.tool.ToolResult;
import com.sjherp.agent.tool.ToolRiskLevel;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.agent.JacksonToolArgumentsCodec;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;
import com.sjherp.infra.agent.PendingToolCallJsonCodec;

/**
 * ChatService 高风险工具确认流程测试（M1-T03 验收）：
 * 对话触发高风险工具 → 框架拦截 → 确认卡片（requiresConfirmation=true + 固定选项 id）
 * → 现场存入会话 → 点确认恢复执行 / 点取消清现场，全链路用假 LlmClient 驱动。
 */
class ChatServiceToolConfirmationTest {

    /** 按脚本逐次返回响应的假 LLM */
    static final class ScriptedLlmClient implements LlmClient {

        final List<LlmResponse> scripted = new ArrayList<>();
        private int index;

        @Override
        public LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options) {
            if (index >= scripted.size()) {
                throw new IllegalStateException("脚本响应用尽（第 " + (index + 1) + " 次调用）");
            }
            return scripted.get(index++);
        }
    }

    /** 计数版高风险工具（结构同 DemoHighRiskTool，便于断言执行次数） */
    static final class CountingHighRiskTool implements Tool {

        int executions;

        @Override
        public String name() {
            return "demo_post_document";
        }

        @Override
        public String description() {
            return "演示版单据过账（高风险）";
        }

        @Override
        public String parameterSchema() {
            return "{\"type\":\"object\",\"properties\":{\"documentId\":{\"type\":\"string\"}},"
                    + "\"required\":[\"documentId\"]}";
        }

        @Override
        public ToolRiskLevel riskLevel() {
            return ToolRiskLevel.HIGH;
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
            executions++;
            return ToolResult.ok(Map.of("documentId", arguments.get("documentId"), "status", "POSTED"));
        }
    }

    private ScriptedLlmClient llm;
    private CountingHighRiskTool highRiskTool;
    private InMemoryAgentSessionRepository repository;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        llm = new ScriptedLlmClient();
        highRiskTool = new CountingHighRiskTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(highRiskTool);

        AgentReplyJsonCodec codec = new AgentReplyJsonCodec();
        AgentLoop loop = new AgentLoop(llm, new JacksonToolArgumentsCodec(),
                new JsonSchemaToolArgumentValidator(), ToolPermissionChecker.allowAll());
        // 测试用 JSON_WITH_TOOLS：每轮一次调用，脚本编排最简单（真实 DeepSeek 用 separate-final-call）
        LlmAgent agent = new LlmAgent(loop, codec, new PendingToolCallJsonCodec(), registry,
                FinalJsonMode.JSON_WITH_TOOLS, 8, Duration.ofSeconds(30));

        repository = new InMemoryAgentSessionRepository();
        chatService = new ChatService(repository, codec, agent);
    }

    private static LlmResponse toolCallResponse() {
        return new LlmResponse(null, List.of(
                new ToolCall("call-1", "demo_post_document", "{\"documentId\":\"DOC-1\"}")));
    }

    private static LlmResponse finalText(String text) {
        return new LlmResponse("{\"version\":\"0.1\",\"text\":\"" + text + "\"}");
    }

    @Test
    void highRiskCallTriggersConfirmationCardAndPersistsPendingState() {
        llm.scripted.add(toolCallResponse());

        AgentSession session = chatService.createSession("test-user");
        AgentReply reply = chatService.handleMessage(session.getSessionId(), "test-user",
                new SendMessageRequest("把单据 DOC-1 过账", null, null, null));

        // 工具未执行，回复是确认卡片：requiresConfirmation=true + 固定选项 id + 确认项 risk=high
        assertThat(highRiskTool.executions).isZero();
        assertThat(reply.requiresConfirmation()).isTrue();
        assertThat(reply.text()).contains("demo_post_document").contains("高风险");
        assertThat(reply.options()).extracting(Option::id)
                .containsExactly(ToolConfirmation.CONFIRM_OPTION_ID, ToolConfirmation.CANCEL_OPTION_ID);
        assertThat(reply.options().get(0).risk()).isEqualTo(Option.RiskLevel.HIGH);
        assertThat(reply.options().get(1).risk()).isEqualTo(Option.RiskLevel.NORMAL);

        // 现场已随会话落库（杀进程可恢复）
        AgentSession persisted = repository.findById(session.getSessionId()).orElseThrow();
        assertThat(persisted.hasPendingToolCall()).isTrue();
        assertThat(persisted.getPendingToolCallJson()).contains("demo_post_document").contains("DOC-1");
    }

    @Test
    void confirmOptionResumesExecutionAndClearsPendingState() {
        llm.scripted.add(toolCallResponse());
        llm.scripted.add(finalText("单据 DOC-1 已成功过账"));

        AgentSession session = chatService.createSession("test-user");
        chatService.handleMessage(session.getSessionId(), "test-user",
                new SendMessageRequest("把单据 DOC-1 过账", null, null, null));

        // 用户点击「确认执行」（前端只回传固定 optionId）
        AgentReply reply = chatService.handleMessage(session.getSessionId(), "test-user",
                new SendMessageRequest(null, ToolConfirmation.CONFIRM_OPTION_ID, null, null));

        assertThat(highRiskTool.executions).isEqualTo(1);
        assertThat(reply.requiresConfirmation()).isFalse();
        assertThat(reply.text()).contains("DOC-1 已成功过账");

        AgentSession persisted = repository.findById(session.getSessionId()).orElseThrow();
        assertThat(persisted.hasPendingToolCall()).isFalse();
    }

    @Test
    void cancelOptionSkipsExecutionAndClearsPendingState() {
        llm.scripted.add(toolCallResponse());
        llm.scripted.add(finalText("好的，已取消过账操作"));

        AgentSession session = chatService.createSession("test-user");
        chatService.handleMessage(session.getSessionId(), "test-user",
                new SendMessageRequest("把单据 DOC-1 过账", null, null, null));

        AgentReply reply = chatService.handleMessage(session.getSessionId(), "test-user",
                new SendMessageRequest(null, ToolConfirmation.CANCEL_OPTION_ID, null, null));

        assertThat(highRiskTool.executions).isZero();
        assertThat(reply.text()).contains("已取消");
        assertThat(repository.findById(session.getSessionId()).orElseThrow().hasPendingToolCall()).isFalse();
    }

    @Test
    void freeTextWhilePendingIsTreatedAsCancel() {
        llm.scripted.add(toolCallResponse());
        llm.scripted.add(finalText("好的，先不过账了"));

        AgentSession session = chatService.createSession("test-user");
        chatService.handleMessage(session.getSessionId(), "test-user",
                new SendMessageRequest("把单据 DOC-1 过账", null, null, null));

        // 待确认期间用户改发自由文本：按取消语义恢复，工具一律不执行
        AgentReply reply = chatService.handleMessage(session.getSessionId(), "test-user",
                new SendMessageRequest("等等，先不要", null, null, null));

        assertThat(highRiskTool.executions).isZero();
        assertThat(reply.text()).isNotBlank();
        assertThat(repository.findById(session.getSessionId()).orElseThrow().hasPendingToolCall()).isFalse();
    }
}
