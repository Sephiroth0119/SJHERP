package com.sjherp.agent.session;

import java.time.Instant;

/**
 * 会话中的一条消息（持久化时单独成表）。
 *
 * @param role      消息角色
 * @param content   消息内容；assistant 消息存放结构化回复（AgentReply）序列化后的 JSON，
 *                  tool 消息存放工具执行结果的 JSON
 * @param createdAt 产生时间
 */
public record AgentMessage(MessageRole role, String content, Instant createdAt) {

    public static AgentMessage user(String content) {
        return new AgentMessage(MessageRole.USER, content, Instant.now());
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage(MessageRole.ASSISTANT, content, Instant.now());
    }

    public static AgentMessage tool(String content) {
        return new AgentMessage(MessageRole.TOOL, content, Instant.now());
    }
}
