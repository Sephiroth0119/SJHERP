package com.sjherp.agent.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.session.MessageRole;

/**
 * 历史窗口裁剪纯函数测试（M1-T05 验收）：
 * 不超阈值不动 / 超阈值正确切分 / 摘要回调被正确调用 / 回调失败硬截断 /
 * 既有摘要合并与覆盖范围过滤 / 近期轮数不足不裁剪。
 */
class HistoryTrimmerTest {

    /** 记录调用入参的假摘要回调 */
    static final class RecordingSummarizer implements HistorySummarizer {

        String receivedPreviousSummary;
        List<HistoryMessage> receivedMessages;
        String result = "摘要内容";
        RuntimeException toThrow;
        int invocations;

        @Override
        public String summarize(String previousSummary, List<HistoryMessage> messages) {
            invocations++;
            receivedPreviousSummary = previousSummary;
            receivedMessages = messages;
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    /**
     * 构造 rounds 轮对话（每轮 USER + ASSISTANT 各一条），seq 从 1 连续递增；
     * 每条内容为 40 个 ASCII 字符 ≈ 10 token，便于精确控制预算。
     */
    private static List<HistoryMessage> rounds(int rounds) {
        List<HistoryMessage> messages = new ArrayList<>();
        int seq = 1;
        for (int r = 1; r <= rounds; r++) {
            messages.add(new HistoryMessage(seq++, MessageRole.USER, "u" + r + "-" + "x".repeat(36)));
            messages.add(new HistoryMessage(seq++, MessageRole.ASSISTANT, "a" + r + "-" + "x".repeat(36)));
        }
        return messages;
    }

    @Test
    void underBudgetReturnsUnchangedAndNeverCallsSummarizer() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        HistoryTrimmer trimmer = new HistoryTrimmer(10_000, 2);
        List<HistoryMessage> messages = rounds(5); // 约 100 token，远低于预算

        HistoryTrimResult result = trimmer.trim(messages, null, 0, summarizer);

        assertEquals(0, summarizer.invocations);
        assertNull(result.summary());
        assertEquals(0, result.summarizedUntilSeq());
        assertEquals(messages, result.recentMessages());
        assertFalse(result.summaryUpdated());
        assertFalse(result.hardTruncated());
    }

    @Test
    void overBudgetCompressesOldestRoundsAndKeepsRecent() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        HistoryTrimmer trimmer = new HistoryTrimmer(50, 2);
        List<HistoryMessage> messages = rounds(10); // 约 200 token，超预算

        HistoryTrimResult result = trimmer.trim(messages, null, 0, summarizer);

        // 摘要回调收到最旧 8 轮（16 条，seq 1..16），previousSummary 为 null
        assertEquals(1, summarizer.invocations);
        assertNull(summarizer.receivedPreviousSummary);
        assertEquals(16, summarizer.receivedMessages.size());
        assertEquals(1, summarizer.receivedMessages.get(0).seq());
        assertEquals(16, summarizer.receivedMessages.get(15).seq());
        // 结果：新摘要生效，覆盖到 seq 16；窗口只剩最近 2 轮（seq 17..20，以 USER 开头）
        assertTrue(result.summaryUpdated());
        assertEquals("摘要内容", result.summary());
        assertEquals(16, result.summarizedUntilSeq());
        assertEquals(4, result.recentMessages().size());
        assertEquals(17, result.recentMessages().get(0).seq());
        assertEquals(MessageRole.USER, result.recentMessages().get(0).role());
        assertFalse(result.hardTruncated());
    }

    @Test
    void existingSummaryIsPassedToCallbackAndCoveredMessagesExcluded() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        summarizer.result = "合并后的摘要";
        HistoryTrimmer trimmer = new HistoryTrimmer(50, 2);
        // 既有摘要已覆盖到 seq 8（前 4 轮）；剩余 6 轮约 120 token，仍超预算
        List<HistoryMessage> messages = rounds(10);

        HistoryTrimResult result = trimmer.trim(messages, "旧摘要要点", 8, summarizer);

        // 回调收到既有摘要 + seq 9..16（再压缩 4 轮，保留最近 2 轮）
        assertEquals("旧摘要要点", summarizer.receivedPreviousSummary);
        assertEquals(9, summarizer.receivedMessages.get(0).seq());
        assertEquals(16, summarizer.receivedMessages.get(summarizer.receivedMessages.size() - 1).seq());
        assertEquals("合并后的摘要", result.summary());
        assertEquals(16, result.summarizedUntilSeq());
        assertEquals(17, result.recentMessages().get(0).seq());
    }

    @Test
    void summarizerFailureFallsBackToHardTruncation() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        summarizer.toThrow = new IllegalStateException("LLM 调用超时");
        HistoryTrimmer trimmer = new HistoryTrimmer(50, 2);

        HistoryTrimResult result = trimmer.trim(rounds(10), "旧摘要", 4, summarizer);

        // 硬截断：摘要状态原样保留（下一轮重试），窗口只带最近 2 轮，不阻塞对话
        assertTrue(result.hardTruncated());
        assertFalse(result.summaryUpdated());
        assertEquals("旧摘要", result.summary());
        assertEquals(4, result.summarizedUntilSeq());
        assertEquals(4, result.recentMessages().size());
        assertEquals(17, result.recentMessages().get(0).seq());
        assertEquals("LLM 调用超时", result.failureReason());
    }

    @Test
    void blankSummaryFromCallbackTreatedAsFailure() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        summarizer.result = "   ";
        HistoryTrimmer trimmer = new HistoryTrimmer(50, 2);

        HistoryTrimResult result = trimmer.trim(rounds(10), null, 0, summarizer);

        assertTrue(result.hardTruncated());
        assertNull(result.summary());
        assertNotNull(result.failureReason());
    }

    @Test
    void nullSummarizerHardTruncatesWhenOverBudget() {
        HistoryTrimmer trimmer = new HistoryTrimmer(50, 2);

        HistoryTrimResult result = trimmer.trim(rounds(10), null, 0, null);

        assertTrue(result.hardTruncated());
        assertEquals(4, result.recentMessages().size());
    }

    @Test
    void overBudgetButNotEnoughRoundsToCompressKeepsAll() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        HistoryTrimmer trimmer = new HistoryTrimmer(10, 6);
        List<HistoryMessage> messages = rounds(4); // 超预算但只有 4 轮 <= keepRecentRounds=6

        HistoryTrimResult result = trimmer.trim(messages, null, 0, summarizer);

        // 软约束：绝不破坏最近上下文，原样返回
        assertEquals(0, summarizer.invocations);
        assertEquals(messages, result.recentMessages());
        assertFalse(result.summaryUpdated());
        assertFalse(result.hardTruncated());
    }

    @Test
    void summaryTokensCountTowardsBudget() {
        RecordingSummarizer summarizer = new RecordingSummarizer();
        // 消息本身 4 轮 ≈ 80 token，预算 100；但既有摘要 200 个汉字 = 200 token → 触发压缩
        HistoryTrimmer trimmer = new HistoryTrimmer(100, 2);
        List<HistoryMessage> messages = rounds(4);

        HistoryTrimResult result = trimmer.trim(messages, "摘".repeat(200), 0, summarizer);

        assertEquals(1, summarizer.invocations);
        assertTrue(result.summaryUpdated());
        assertEquals(4, result.summarizedUntilSeq()); // 压缩最旧 2 轮（seq 1..4）
        assertEquals(4, result.recentMessages().size());
    }

    @Test
    void invalidConstructorArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryTrimmer(0, 6));
        assertThrows(IllegalArgumentException.class, () -> new HistoryTrimmer(8000, 0));
    }

    @Test
    void emptyAndNullMessageListsAreSafe() {
        HistoryTrimmer trimmer = new HistoryTrimmer(8000, 6);
        assertTrue(trimmer.trim(List.of(), null, 0, null).recentMessages().isEmpty());
        assertTrue(trimmer.trim(null, null, 0, null).recentMessages().isEmpty());
    }
}
