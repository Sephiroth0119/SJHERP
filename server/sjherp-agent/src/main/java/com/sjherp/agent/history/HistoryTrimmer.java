package com.sjherp.agent.history;

import java.util.ArrayList;
import java.util.List;

import com.sjherp.agent.session.MessageRole;

/**
 * 会话历史窗口裁剪（M1-T05 会话上下文治理，还技术债 D-2）。
 *
 * <p>零依赖纯函数：输入消息列表 + 既有摘要 + token 预算 + 摘要回调，输出裁剪结果，
 * 不做任何 IO、不持有任何状态（构造参数只是两个阈值）。
 *
 * <p>规则：
 * <ul>
 *   <li>触发条件：既有摘要 + 未被摘要覆盖的消息，估算 token 总量（{@link TokenEstimator}）
 *       超过预算 → 把最旧的若干轮压缩为一段摘要，保留最近 {@code keepRecentRounds} 轮；</li>
 *   <li>「一轮」= 一条 USER 消息及其后到下一条 USER 消息之前的全部消息；</li>
 *   <li>摘要经 {@link HistorySummarizer} 回调生成（既有摘要会一并交给回调合并）；</li>
 *   <li>回调失败 → 硬截断兜底：本次只带最近 N 轮进上下文，摘要状态不变，
 *       不阻塞对话（完整历史仍在库中，下一次对话会重试摘要）；</li>
 *   <li>近期轮数不足 keepRecentRounds + 1 时即便超预算也不裁剪（预算是软约束，
 *       绝不破坏最近上下文的完整性）。</li>
 * </ul>
 *
 * <p>裁剪只影响发给 LLM 的上下文；完整历史仍在 agent_message 表，会话回放 API 不受影响。
 */
public final class HistoryTrimmer {

    private final int tokenBudget;
    private final int keepRecentRounds;

    /**
     * @param tokenBudget      历史窗口 token 预算（启发式估算口径，见 {@link TokenEstimator}）
     * @param keepRecentRounds 压缩时保留的最近对话轮数
     */
    public HistoryTrimmer(int tokenBudget, int keepRecentRounds) {
        if (tokenBudget < 1) {
            throw new IllegalArgumentException("tokenBudget 必须 >= 1（实际 " + tokenBudget + "）");
        }
        if (keepRecentRounds < 1) {
            throw new IllegalArgumentException("keepRecentRounds 必须 >= 1（实际 " + keepRecentRounds + "）");
        }
        this.tokenBudget = tokenBudget;
        this.keepRecentRounds = keepRecentRounds;
    }

    /**
     * 裁剪一次历史窗口。
     *
     * @param messages           会话全部候选消息（按 seq 升序；调用方只传 USER / ASSISTANT）
     * @param existingSummary    会话上既有的摘要（null = 尚未摘要过）
     * @param summarizedUntilSeq 既有摘要覆盖到的 seq（0 = 未覆盖）；seq 在此之前的消息不再进入上下文
     * @param summarizer         摘要回调（null 视为摘要不可用，需要压缩时直接硬截断）
     */
    public HistoryTrimResult trim(List<HistoryMessage> messages, String existingSummary,
                                  int summarizedUntilSeq, HistorySummarizer summarizer) {
        // 1. 已被既有摘要覆盖的消息不再进入上下文
        List<HistoryMessage> effective = new ArrayList<>();
        if (messages != null) {
            for (HistoryMessage message : messages) {
                if (message.seq() > summarizedUntilSeq) {
                    effective.add(message);
                }
            }
        }

        // 2. 估算总量（摘要 + 未覆盖消息），不超预算则原样返回
        int total = TokenEstimator.estimate(existingSummary);
        for (HistoryMessage message : effective) {
            total += TokenEstimator.estimate(message.content());
        }
        if (total <= tokenBudget) {
            return new HistoryTrimResult(existingSummary, summarizedUntilSeq, effective, false, false, null);
        }

        // 3. 按 USER 消息划轮，保留最近 keepRecentRounds 轮，其余压缩
        List<Integer> roundStarts = new ArrayList<>();
        for (int i = 0; i < effective.size(); i++) {
            if (effective.get(i).role() == MessageRole.USER) {
                roundStarts.add(i);
            }
        }
        if (roundStarts.size() <= keepRecentRounds) {
            // 超预算但已无更旧的轮可裁：原样返回（软约束，不破坏最近上下文）
            return new HistoryTrimResult(existingSummary, summarizedUntilSeq, effective, false, false, null);
        }
        int cut = roundStarts.get(roundStarts.size() - keepRecentRounds);
        // 第一条 USER 之前若有残留消息（理论上不出现），一并归入压缩段
        List<HistoryMessage> toCompress = List.copyOf(effective.subList(0, cut));
        List<HistoryMessage> recent = List.copyOf(effective.subList(cut, effective.size()));

        // 4. 生成摘要；失败 → 硬截断兜底（摘要状态不变，下一次对话重试）
        try {
            if (summarizer == null) {
                throw new IllegalStateException("未提供摘要回调（summarizer 为 null）");
            }
            String newSummary = summarizer.summarize(existingSummary, toCompress);
            if (newSummary == null || newSummary.isBlank()) {
                throw new IllegalStateException("摘要回调返回空内容");
            }
            int newUntilSeq = toCompress.get(toCompress.size() - 1).seq();
            return new HistoryTrimResult(newSummary.strip(), newUntilSeq, recent, true, false, null);
        } catch (RuntimeException e) {
            return new HistoryTrimResult(existingSummary, summarizedUntilSeq, recent, false, true,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
