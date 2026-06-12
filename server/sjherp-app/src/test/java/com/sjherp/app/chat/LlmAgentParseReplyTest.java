package com.sjherp.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.loop.AgentLoop;
import com.sjherp.agent.loop.FinalJsonMode;
import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.agent.JacksonToolArgumentsCodec;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;
import com.sjherp.infra.agent.PendingToolCallJsonCodec;

/**
 * LlmAgent 对模型输出的协议解析降级路径单测（X-2 交叉校验盲区）：
 * <ul>
 *   <li>非法 JSON → 原始文本包成纯文本 AgentReply 兜底（不向用户抛异常）；</li>
 *   <li>markdown 代码块包裹的合法协议 JSON → 正确剥离后解析；</li>
 *   <li>缺 version 字段 → AgentReply 构造器补默认 "0.1"；</li>
 *   <li>空内容 → 固定提示文本。</li>
 * </ul>
 * parseReply 为私有方法，经公开入口 replyToText（假 LlmClient 注入原始输出）验证。
 */
class LlmAgentParseReplyTest {

    /** 返回固定原始内容的假 LLM */
    private static final class FixedLlmClient implements LlmClient {

        private final String raw;

        FixedLlmClient(String raw) {
            this.raw = raw;
        }

        @Override
        public LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options) {
            return new LlmResponse(raw);
        }
    }

    /** 构造无工具的 LlmAgent（单轮对话；JSON_WITH_TOOLS 保证只发一次 LLM 调用） */
    private static AgentReply replyFor(String rawModelOutput) {
        AgentLoop loop = new AgentLoop(new FixedLlmClient(rawModelOutput),
                new JacksonToolArgumentsCodec(), new JsonSchemaToolArgumentValidator(),
                ToolPermissionChecker.allowAll());
        LlmAgent agent = new LlmAgent(loop, new AgentReplyJsonCodec(), new PendingToolCallJsonCodec(),
                new ToolRegistry(), FinalJsonMode.JSON_WITH_TOOLS, 8, Duration.ofSeconds(30));
        return agent.replyToText(new AgentSession("session-1", "user-1"), "你好");
    }

    @Test
    void 非法JSON_降级为纯文本回复() {
        String raw = "库存还有 3 件，需要补货吗？";

        AgentReply reply = replyFor(raw);

        // 原始文本原样兜底；version 由构造器补默认值
        assertThat(reply.text()).isEqualTo(raw);
        assertThat(reply.version()).isEqualTo(AgentReply.PROTOCOL_VERSION);
        assertThat(reply.options()).isEmpty();
        assertThat(reply.form()).isNull();
        assertThat(reply.requiresConfirmation()).isFalse();
    }

    @Test
    void 结构残缺的JSON_同样降级为纯文本() {
        // 是 JSON 但缺必填 text 字段 → 解析失败走兜底，用户看到原始内容而非异常
        String raw = "{\"version\":\"0.1\",\"foo\":\"bar\"}";

        AgentReply reply = replyFor(raw);

        assertThat(reply.text()).isEqualTo(raw);
        assertThat(reply.options()).isEmpty();
    }

    @Test
    void markdown代码块包裹的协议JSON_正确剥离解析() {
        String raw = """
                ```json
                {"version":"0.1","text":"你好，需要帮忙吗？","options":[{"id":"opt-1","label":"查询库存"}]}
                ```""";

        AgentReply reply = replyFor(raw);

        assertThat(reply.text()).isEqualTo("你好，需要帮忙吗？");
        assertThat(reply.options()).hasSize(1);
        assertThat(reply.options().get(0).id()).isEqualTo("opt-1");
        assertThat(reply.options().get(0).label()).isEqualTo("查询库存");
    }

    @Test
    void 缺version字段_解析成功并补默认协议版本() {
        String raw = "{\"text\":\"无版本字段的回复\"}";

        AgentReply reply = replyFor(raw);

        assertThat(reply.version()).isEqualTo("0.1");
        assertThat(reply.text()).isEqualTo("无版本字段的回复");
    }

    @Test
    void 模型返回空内容_固定提示文本兜底() {
        AgentReply reply = replyFor("   ");

        assertThat(reply.text()).isEqualTo("（模型未返回内容，请重试）");
        assertThat(reply.version()).isEqualTo("0.1");
    }
}
