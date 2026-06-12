package com.sjherp.agent.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.LlmUsage;
import com.sjherp.agent.loop.AgentInvocationListener;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.tool.ToolRiskLevel;

/**
 * LlmHistorySummarizer 单元测试（M1-T07 摘要观测回调）：
 * 摘要 LLM 调用经 onAuxiliaryLlmCall 上报（purpose=summarize、sessionId、token 用量）、
 * 失败也上报且异常照常抛出（硬截断兜底语义不变）、观测回调自身异常被吞掉。
 */
class LlmHistorySummarizerTest {

    /** 记录辅助调用回调入参的假 listener */
    static final class RecordingListener implements AgentInvocationListener {

        record AuxCall(String sessionId, String purpose, String model, long durationMs,
                       Integer promptTokens, Integer completionTokens, String error) { }

        final List<AuxCall> auxCalls = new ArrayList<>();
        RuntimeException toThrow;

        @Override
        public void onLlmCall(String sessionId, int round, String model, long durationMs,
                              Integer promptTokens, Integer completionTokens,
                              boolean hasToolCalls, String error) {
            throw new IllegalStateException("摘要调用不应走 onLlmCall（那是 AgentLoop 主链路口径）");
        }

        @Override
        public void onToolCall(String sessionId, String toolName, String argumentsJson, boolean success,
                               String resultSummary, long durationMs, ToolRiskLevel riskLevel,
                               boolean confirmed) {
            throw new IllegalStateException("摘要调用不应走 onToolCall");
        }

        @Override
        public void onAuxiliaryLlmCall(String sessionId, String purpose, String model, long durationMs,
                                       Integer promptTokens, Integer completionTokens, String error) {
            auxCalls.add(new AuxCall(sessionId, purpose, model, durationMs,
                    promptTokens, completionTokens, error));
            if (toThrow != null) {
                throw toThrow;
            }
        }
    }

    private static List<HistoryMessage> messages() {
        return List.of(
                new HistoryMessage(1, MessageRole.USER, "客户甲订购商品 A，金额 1888.88 元"),
                new HistoryMessage(2, MessageRole.ASSISTANT, "好的，已记录"));
    }

    @Test
    void successfulSummaryReportsAuxiliaryCallWithSessionIdAndUsage() {
        LlmClient llm = (msgs, options) -> new LlmResponse(
                "- 客户甲订购商品 A，金额 1888.88 元", List.of(), "deepseek-chat-v3", new LlmUsage(321, 87));
        RecordingListener listener = new RecordingListener();
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(llm, listener);

        // forSession 绑定会话 id（LlmAgent 在裁剪前调用）
        String summary = summarizer.forSession("session-1").summarize(null, messages());

        assertEquals("- 客户甲订购商品 A，金额 1888.88 元", summary);
        assertEquals(1, listener.auxCalls.size());
        RecordingListener.AuxCall call = listener.auxCalls.get(0);
        assertEquals("session-1", call.sessionId());
        assertEquals("summarize", call.purpose());
        assertEquals("deepseek-chat-v3", call.model());
        assertEquals(321, call.promptTokens());
        assertEquals(87, call.completionTokens());
        assertNull(call.error());
        assertTrue(call.durationMs() >= 0);
    }

    @Test
    void unboundSummarizeReportsNullSessionId() {
        LlmClient llm = (msgs, options) -> new LlmResponse("- 摘要要点");
        RecordingListener listener = new RecordingListener();

        new LlmHistorySummarizer(llm, listener).summarize(null, messages());

        assertNull(listener.auxCalls.get(0).sessionId());
        assertEquals("summarize", listener.auxCalls.get(0).purpose());
    }

    @Test
    void llmFailureReportsErrorAndStillThrows() {
        LlmClient llm = (msgs, options) -> {
            throw new IllegalStateException("网络超时");
        };
        RecordingListener listener = new RecordingListener();
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(llm, listener);

        // 异常照常抛出：HistoryTrimmer 的硬截断兜底语义不变
        assertThrows(IllegalStateException.class,
                () -> summarizer.forSession("session-1").summarize(null, messages()));

        RecordingListener.AuxCall call = listener.auxCalls.get(0);
        assertEquals("session-1", call.sessionId());
        assertEquals("网络超时", call.error());
        assertNull(call.model());
    }

    @Test
    void blankContentReportsErrorAndThrows() {
        LlmClient llm = (msgs, options) -> new LlmResponse("  ", List.of(), "deepseek-chat", new LlmUsage(10, 0));
        RecordingListener listener = new RecordingListener();
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(llm, listener);

        assertThrows(IllegalStateException.class, () -> summarizer.summarize(null, messages()));

        RecordingListener.AuxCall call = listener.auxCalls.get(0);
        assertEquals("摘要模型返回空内容", call.error());
        assertEquals("deepseek-chat", call.model());
        assertEquals(10, call.promptTokens());
    }

    @Test
    void listenerFailureIsSwallowedAndSummaryStillReturned() {
        LlmClient llm = (msgs, options) -> new LlmResponse("- 摘要要点");
        RecordingListener listener = new RecordingListener();
        listener.toThrow = new IllegalStateException("观测落库挂了");
        LlmHistorySummarizer summarizer = new LlmHistorySummarizer(llm, listener);

        // 观测失败绝不影响摘要主流程
        assertEquals("- 摘要要点", summarizer.forSession("session-1").summarize(null, messages()));
        assertEquals(1, listener.auxCalls.size());
    }

    @Test
    void nullListenerMeansNoObservation() {
        LlmClient llm = (msgs, options) -> new LlmResponse("- 摘要要点");
        // 单参构造（既有调用方口径）：不观测也能正常摘要
        assertEquals("- 摘要要点", new LlmHistorySummarizer(llm).summarize(null, messages()));
    }

    @Test
    void promptStillCarriesPreviousSummaryAndMessages() {
        List<List<LlmMessage>> captured = new ArrayList<>();
        LlmClient llm = (msgs, options) -> {
            captured.add(msgs);
            // 摘要走低温度（0.2）
            assertEquals(0.2, options.temperature().doubleValue());
            return new LlmResponse("- 合并后的摘要");
        };

        new LlmHistorySummarizer(llm, new RecordingListener())
                .forSession("s").summarize("旧摘要要点", messages());

        String userContent = captured.get(0).get(1).content();
        assertTrue(userContent.contains("旧摘要要点"));
        assertTrue(userContent.contains("1888.88"));
    }
}
