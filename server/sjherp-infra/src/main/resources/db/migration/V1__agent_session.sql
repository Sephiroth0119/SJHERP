-- V1：Agent 会话持久化（ADR-001 路线 C：任意时刻杀进程，会话可从数据库完整恢复）
-- 字符集统一 utf8mb4；时间列用 DATETIME(6)，应用层一律按 UTC 读写（与连接时区解耦）。

-- 会话表（对应 com.sjherp.agent.session.AgentSession）
CREATE TABLE agent_session (
    id         VARCHAR(36)  NOT NULL COMMENT '会话唯一标识（UUID）',
    user_id    VARCHAR(64)  NOT NULL COMMENT '发起用户（审计要求：动作可追溯到人；当前无登录体系，为占位用户）',
    title      VARCHAR(200) NULL     COMMENT '会话标题（默认取首条用户消息摘要）',
    status     VARCHAR(20)  NOT NULL COMMENT '会话状态：ACTIVE / WAITING_USER / CLOSED',
    created_at DATETIME(6)  NOT NULL COMMENT '创建时间（UTC）',
    updated_at DATETIME(6)  NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    -- 会话列表页查询：按用户取会话并按最近更新排序
    KEY idx_agent_session_user (user_id, updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT 'Agent 会话';

-- 消息表（对应 com.sjherp.agent.session.AgentMessage，只追加不修改）
CREATE TABLE agent_message (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '消息物理主键',
    session_id VARCHAR(36)     NOT NULL COMMENT '所属会话',
    seq        INT             NOT NULL COMMENT '会话内序号（从 1 开始，回放按 seq 升序）',
    role       VARCHAR(16)     NOT NULL COMMENT '消息角色：SYSTEM / USER / ASSISTANT / TOOL',
    content    TEXT            NOT NULL COMMENT '消息内容：用户文本，或 AgentReply 序列化后的 JSON（选项返回协议 v0.1）',
    created_at DATETIME(6)     NOT NULL COMMENT '产生时间（UTC）',
    PRIMARY KEY (id),
    -- 会话内序号唯一，且天然覆盖"按会话取消息并排序"的查询
    UNIQUE KEY uk_agent_message_session_seq (session_id, seq),
    CONSTRAINT fk_agent_message_session FOREIGN KEY (session_id) REFERENCES agent_session (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT 'Agent 会话消息';
