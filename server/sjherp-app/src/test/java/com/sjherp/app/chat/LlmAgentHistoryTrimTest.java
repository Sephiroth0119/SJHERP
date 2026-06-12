package com.sjherp.app.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.history.HistorySummarizer;
import com.sjherp.agent.history.HistoryTrimmer;
import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.loop.AgentLoop;
import com.sjherp.agent.loop.FinalJsonMode;
import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.agent.JacksonToolArgumentsCodec;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;
import com.sjherp.infra.agent.PendingToolCallJsonCodec;

/**
 * LlmAgent 历史窗口裁剪集成测试（M1-T05）：假 LlmClient 捕获实际发出的消息，
 * 验证摘要触发后的消息结构——system 提示含摘要小节、窗口只带最近 N 轮、
 * 摘要写回会话；摘要失败时硬截断且对话不中断。
 */
class LlmAgentHistoryTrimTest {

    private static final String FINAL_JSON = "{\"version\":\"0.1\",\"text\":\"好的，已收到\"}";

    /** 捕获最近一次请求消息的假 LLM */
    static final class CapturingLlmClient implements LlmClient {

        List<LlmMessage> lastMessages;

        @Override
        public LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options) {
            lastMessages = messages;
            return new LlmResponse(FINAL_JSON);
        }
    }

    /** 构造带 rounds 轮历史的会话（每轮 USER + ASSISTANT，每条约 10 token 的 ASCII 内容） */
    private static AgentSession sessionWithRounds(int rounds) {
        AgentSession session = new AgentSession("session-1", "user-1");
        for (int r = 1; r <= rounds; r++) {
            session.append(AgentMessage.user("question-" + r + "-" + "x".repeat(30)));
            session.append(AgentMessage.assistant("answer-" + r + "-" + "y".repeat(30)));
        }
        return session;
    }

    private static LlmAgent agent(CapturingLlmClient llm, HistoryTrimmer trimmer,
                                  HistorySummarizer summarizer) {
        AgentLoop loop = new AgentLoop(llm, new JacksonToolArgumentsCodec(),
                new JsonSchemaToolArgumentValidator(), ToolPermissionChecker.allowAll());
        // ToolRegistry 为空 → 单轮对话；JSON_WITH_TOOLS 保证只发一次 LLM 调用，便于断言
        return new LlmAgent(loop, new AgentReplyJsonCodec(), new PendingToolCallJsonCodec(),
                new ToolRegistry(), FinalJsonMode.JSON_WITH_TOOLS, 8, Duration.ofSeconds(30),
                trimmer, summarizer);
    }

    @Test
    void underBudgetSendsFullHistoryWithoutSummary() {
        CapturingLlmClient llm = new CapturingLlmClient();
        LlmAgent agent = agent(llm, new HistoryTrimmer(8000, 6),
                (prev, msgs) -> {
                    throw new IllegalStateException("不应触发摘要");
                });
        AgentSession session = sessionWithRounds(3);

        AgentReply reply = agent.replyToText(session, "当前输入");

        assertThat(reply.text()).isEqualTo("好的，已收到");
        // system + 3 轮历史(6 条) + 当前输入
        assertThat(llm.lastMessages).hasSize(8);
        assertThat(llm.lastMessages.get(0).content()).doesNotContain("早前对话摘要");
        assertThat(session.getHistorySummary()).isNull();
        assertThat(session.getSummarizedUntilSeq()).isZero();
    }

    @Test
    void overBudgetInjectsSummaryIntoSystemPromptAndKeepsRecentRounds() {
        CapturingLlmClient llm = new CapturingLlmClient();
        List<String> compressedContents = new ArrayList<>();
        HistorySummarizer summarizer = (prev, msgs) -> {
            msgs.forEach(m -> compressedContents.add(m.content()));
            return "- 客户甲订购商品 A，金额 1888.88 元（单据号 SO-20260612-001），尚未付款";
        };
        LlmAgent agent = agent(llm, new HistoryTrimmer(60, 2), summarizer);
        AgentSession session = sessionWithRounds(8); // 约 160 token，超预算

        AgentReply reply = agent.replyToText(session, "当前输入");

        assertThat(reply.text()).isEqualTo("好的，已收到");
        // 摘要进入 system 提示（首条消息），金额原样保留
        LlmMessage system = llm.lastMessages.get(0);
        assertThat(system.role()).isEqualTo(MessageRole.SYSTEM);
        assertThat(system.content()).contains("早前对话摘要").contains("1888.88")
                .contains("SO-20260612-001");
        // 窗口结构：system + 最近 2 轮(4 条) + 当前输入 = 6 条；被压缩的旧消息不再出现
        assertThat(llm.lastMessages).hasSize(6);
        assertThat(llm.lastMessages.get(1).content()).startsWith("question-7");
        assertThat(llm.lastMessages.get(4).content()).startsWith("answer-8");
        assertThat(llm.lastMessages.get(5).content()).isEqualTo("当前输入");
        // 被压缩的是最旧 6 轮（12 条）
        assertThat(compressedContents).hasSize(12);
        assertThat(compressedContents.get(0)).startsWith("question-1");
        // 摘要状态写回会话（随 ChatService.save 落库）
        assertThat(session.getHistorySummary()).contains("1888.88");
        assertThat(session.getSummarizedUntilSeq()).isEqualTo(12);
    }

    @Test
    void summarizerFailureHardTruncatesButConversationContinues() {
        CapturingLlmClient llm = new CapturingLlmClient();
        LlmAgent agent = agent(llm, new HistoryTrimmer(60, 2),
                (prev, msgs) -> {
                    throw new IllegalStateException("LLM 摘要调用失败");
                });
        AgentSession session = sessionWithRounds(8);

        AgentReply reply = agent.replyToText(session, "当前输入");

        // 对话不中断：硬截断后正常返回
        assertThat(reply.text()).isEqualTo("好的，已收到");
        // 窗口：system（无摘要小节）+ 最近 2 轮 + 当前输入
        assertThat(llm.lastMessages).hasSize(6);
        assertThat(llm.lastMessages.get(0).content()).doesNotContain("早前对话摘要");
        assertThat(llm.lastMessages.get(1).content()).startsWith("question-7");
        // 摘要状态不变：下一次对话重试摘要
        assertThat(session.getHistorySummary()).isNull();
        assertThat(session.getSummarizedUntilSeq()).isZero();
    }

    @Test
    void existingSummaryIsReusedWithoutNewCompressionWhenUnderBudget() {
        CapturingLlmClient llm = new CapturingLlmClient();
        LlmAgent agent = agent(llm, new HistoryTrimmer(8000, 2),
                (prev, msgs) -> {
                    throw new IllegalStateException("不应触发摘要");
                });
        // 恢复一个已带摘要状态的会话：摘要覆盖到 seq 12（前 6 轮）
        AgentSession base = sessionWithRounds(8);
        AgentSession session = AgentSession.restore(base.getSessionId(), base.getUserId(), null,
                base.getStatus(), base.getMessages(), null,
                "- 历史摘要：客户乙应收 500 元", 12, base.getCreatedAt(), base.getUpdatedAt());

        agent.replyToText(session, "当前输入");

        // 既有摘要进入 system，被覆盖的消息不再发送
        assertThat(llm.lastMessages.get(0).content()).contains("客户乙应收 500 元");
        assertThat(llm.lastMessages).hasSize(6); // system + 2 轮(4 条) + 当前输入
        assertThat(llm.lastMessages.get(1).content()).startsWith("question-7");
    }
}
