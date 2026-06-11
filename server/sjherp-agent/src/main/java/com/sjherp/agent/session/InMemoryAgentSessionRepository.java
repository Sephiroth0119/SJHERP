package com.sjherp.agent.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话仓储的内存实现（仅用于骨架阶段与单元测试）。
 *
 * <p>注意：生产环境必须替换为数据库实现（sjherp-infra 提供），
 * 否则违反"状态显式持久化"的框架设计约束。
 */
public class InMemoryAgentSessionRepository implements AgentSessionRepository {

    private final Map<String, AgentSession> store = new ConcurrentHashMap<>();

    @Override
    public void save(AgentSession session) {
        store.put(session.getSessionId(), session);
    }

    @Override
    public Optional<AgentSession> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public List<AgentSession> findByUserId(String userId) {
        return store.values().stream()
                .filter(s -> s.getUserId().equals(userId))
                .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                .toList();
    }
}
