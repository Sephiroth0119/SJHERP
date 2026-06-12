package com.sjherp.agent.history;

import java.util.List;

/**
 * 历史摘要回调接口（M1-T05，零依赖）。
 *
 * <p>{@link HistoryTrimmer} 判定需要压缩时调用本接口生成摘要；
 * 实现方（如 {@link LlmHistorySummarizer}）负责真正的摘要生成。
 * 抛出的任何 RuntimeException 都由 HistoryTrimmer 按「摘要失败」兜底：
 * 本次硬截断（只带最近 N 轮进上下文），不阻塞对话。
 */
@FunctionalInterface
public interface HistorySummarizer {

    /**
     * 把一段旧对话压缩为摘要。
     *
     * @param previousSummary 既有摘要（null = 首次摘要）；新摘要必须吸收其要点，不得丢失
     * @param messages        本次要压缩的消息（按 seq 升序，非空）
     * @return 新摘要文本（非空非空白）
     * @throws RuntimeException 摘要生成失败（网络 / 超时 / 空回复等）
     */
    String summarize(String previousSummary, List<HistoryMessage> messages);

    /**
     * 绑定会话 id 的摘要器视图（M1-T07 摘要观测）：实现方若需要把摘要的 LLM 调用
     * 落 agent_invocation 观测（如 {@link LlmHistorySummarizer}），覆写本方法以携带
     * sessionId；默认返回自身（无观测需求的实现与测试替身不受影响）。
     */
    default HistorySummarizer forSession(String sessionId) {
        return this;
    }
}
