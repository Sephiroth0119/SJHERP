package com.sjherp.agent.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 会话列表摘要（不含消息正文）。供「历史会话列表」只读查询，避免为列表
 * 加载全部 agent_message。
 */
public record AgentSessionSummary(
        String sessionId,
        String userId,
        String title,
        SessionStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public AgentSessionSummary {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }
}
