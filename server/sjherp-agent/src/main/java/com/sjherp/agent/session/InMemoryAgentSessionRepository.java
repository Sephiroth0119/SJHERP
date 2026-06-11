package com.sjherp.agent.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话仓储的内存实现（仅用于单元测试，不再作为运行时默认实现）。
 *
 * <p>运行时默认实现为 sjherp-infra 的 JdbcAgentSessionRepository（MySQL），
 * 保证"状态显式持久化"——任意时刻杀进程会话可恢复（ADR-001）。
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
