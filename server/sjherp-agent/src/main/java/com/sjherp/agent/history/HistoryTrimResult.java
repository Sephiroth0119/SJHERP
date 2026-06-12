package com.sjherp.agent.history;

import java.util.List;

/**
 * 历史窗口裁剪结果（M1-T05）。
 *
 * @param summary            当前生效的摘要文本（null = 无摘要）
 * @param summarizedUntilSeq 摘要覆盖到的消息 seq（agent_message.seq；0 = 未覆盖任何消息）
 * @param recentMessages     应进入 LLM 上下文的消息（摘要覆盖范围之后，按 seq 升序）
 * @param summaryUpdated     本次是否生成了新摘要；true 时调用方需把 summary /
 *                           summarizedUntilSeq 写回会话（随会话落库）
 * @param hardTruncated      摘要回调失败导致的硬截断：被裁掉的消息本次既不进上下文
 *                           也没有摘要（完整历史仍在库中，下一次对话会重试摘要）
 * @param failureReason      硬截断的原因（摘要回调抛出的异常信息），仅 hardTruncated=true 时非 null
 */
public record HistoryTrimResult(String summary, int summarizedUntilSeq,
                                List<HistoryMessage> recentMessages,
                                boolean summaryUpdated, boolean hardTruncated,
                                String failureReason) {

    public HistoryTrimResult {
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }

    /** 是否存在生效的摘要 */
    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }
}
