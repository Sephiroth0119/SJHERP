package com.sjherp.agent.llm;

import com.sjherp.agent.session.MessageRole;

/**
 * 提交给 LLM 的一条消息（厂商无关的统一表示）。
 *
 * @param role    角色（复用会话消息角色枚举）
 * @param content 文本内容
 */
public record LlmMessage(MessageRole role, String content) {

    public static LlmMessage system(String content) {
        return new LlmMessage(MessageRole.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(MessageRole.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(MessageRole.ASSISTANT, content);
    }
}
