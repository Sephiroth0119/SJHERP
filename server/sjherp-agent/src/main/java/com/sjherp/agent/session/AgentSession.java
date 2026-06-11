package com.sjherp.agent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Agent 会话。
 *
 * <p>设计约束（CLAUDE.md：状态显式持久化）：会话的全部状态都体现在本类的字段中，
 * 不允许隐藏在内存对象引用里，保证进程重启 / 热部署后可以从数据库完整恢复。
 * 字段即未来表结构的设计依据（sessionId 为主键，messages 单独成表按 seq 排序）。
 */
public class AgentSession {

    /** 会话唯一标识（持久化主键） */
    private final String sessionId;

    /** 发起会话的用户标识（审计要求：每个动作必须可追溯到人） */
    private final String userId;

    /** 会话标题（默认取首条用户消息摘要，供会话列表展示） */
    private String title;

    /** 会话状态 */
    private SessionStatus status;

    /** 消息历史（持久化时单独成表，按追加顺序排列） */
    private final List<AgentMessage> messages = new ArrayList<>();

    /** 创建时间 */
    private final Instant createdAt;

    /** 最后更新时间（每次追加消息 / 状态变更时刷新） */
    private Instant updatedAt;

    public AgentSession(String sessionId, String userId) {
        this(sessionId, userId, null, SessionStatus.ACTIVE, Instant.now(), null);
    }

    /** 全量构造（仅供 {@link #restore} 重建持久化状态时使用） */
    private AgentSession(String sessionId, String userId, String title,
                         SessionStatus status, Instant createdAt, Instant updatedAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId 不能为空");
        this.userId = Objects.requireNonNull(userId, "userId 不能为空");
        this.title = title;
        this.status = Objects.requireNonNull(status, "status 不能为空");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        this.updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * 从持久化状态重建会话（供 infra 仓储实现使用，不刷新 updatedAt）。
     *
     * <p>ADR-001 核心要求：任意时刻杀进程，会话都能凭数据库中的字段完整恢复。
     */
    public static AgentSession restore(String sessionId, String userId, String title,
                                       SessionStatus status, List<AgentMessage> messages,
                                       Instant createdAt, Instant updatedAt) {
        AgentSession session = new AgentSession(sessionId, userId, title, status, createdAt, updatedAt);
        if (messages != null) {
            session.messages.addAll(messages);
        }
        return session;
    }

    /** 追加一条消息并刷新更新时间 */
    public void append(AgentMessage message) {
        Objects.requireNonNull(message, "message 不能为空");
        messages.add(message);
        this.updatedAt = Instant.now();
    }

    /** 关闭会话（关闭后不再接受新消息，仅供查询） */
    public void close() {
        this.status = SessionStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public SessionStatus getStatus() {
        return status;
    }

    /** 只读视图，外部不允许绕过 {@link #append} 修改历史 */
    public List<AgentMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
