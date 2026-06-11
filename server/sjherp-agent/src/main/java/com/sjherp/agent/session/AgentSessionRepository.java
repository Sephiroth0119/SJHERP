package com.sjherp.agent.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话仓储接口。
 *
 * <p>Agent 框架只依赖此抽象；数据库实现由 sjherp-infra 提供（保证热部署 / 重启后可恢复），
 * 当前先提供 {@link InMemoryAgentSessionRepository} 内存实现用于骨架阶段。
 */
public interface AgentSessionRepository {

    /** 保存（新建或覆盖更新）会话 */
    void save(AgentSession session);

    /** 按 ID 查找会话 */
    Optional<AgentSession> findById(String sessionId);

    /** 查询某用户的全部会话（会话列表页） */
    List<AgentSession> findByUserId(String userId);
}
