package com.sjherp.agent.llm;

/**
 * LLM 流式输出回调（厂商无关）。
 *
 * <p>{@link #onContentDelta(String)} 在终轮文本增量到达时调用；
 * {@link #onComplete(LlmResponse)} 在整次调用结束时恰好一次，携带聚合后的完整响应
 * （含 toolCalls / usage，供观测与非流式语义复用）。
 */
public interface LlmStreamHandler {

    /** 文本增量（厂商 delta.content）；空串不会被实现方调用 */
    void onContentDelta(String delta);

    /** 流结束，完整响应。默认空实现。 */
    default void onComplete(LlmResponse response) {
        // 调用方按需覆盖
    }
}
