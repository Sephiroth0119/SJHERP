-- V7：Agent 调用观测记录表（M1-T06，审计原则对 Agent 的延伸；X-6 成本看板数据源）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，应用层暂不读写（恒为 0）。
-- 注意：V6 由并行任务（M2-T05 用户认证）占用，本迁移固定使用 V7。

CREATE TABLE agent_invocation (
    id                BIGINT UNSIGNED    NOT NULL AUTO_INCREMENT COMMENT '调用记录主键',
    tenant_id         BIGINT             NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    session_id        VARCHAR(64)        NULL COMMENT '会话 id（agent_session.session_id；无审计上下文时可空）',
    type              ENUM('LLM','TOOL') NOT NULL COMMENT '调用类型：LLM=一次模型调用；TOOL=一次工具调用',
    model             VARCHAR(64)        NULL COMMENT '模型名（type=LLM；调用失败未获响应时可空）',
    tool_name         VARCHAR(128)       NULL COMMENT '工具名（type=TOOL；模型给出的原始名称，未知工具也原样记录）',
    duration_ms       BIGINT             NOT NULL COMMENT '耗时（毫秒；未实际执行的工具调用为 0）',
    prompt_tokens     INT                NULL COMMENT '输入 token 数（type=LLM；厂商未返回 usage 时可空）',
    completion_tokens INT                NULL COMMENT '输出 token 数（type=LLM；厂商未返回 usage 时可空）',
    success           TINYINT(1)         NOT NULL COMMENT '是否成功（LLM：调用未抛错；TOOL：工具成功执行）',
    detail            JSON               NULL COMMENT '明细：LLM={"round","hasToolCalls","error"}；TOOL={"arguments","resultSummary","riskLevel","confirmed"}',
    created_at        DATETIME(6)        NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    -- 按会话查调用链（时间倒序）+ 会话级 token 汇总
    KEY idx_agent_invocation_session_created (session_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT 'Agent 调用观测记录（每次 LLM 调用 / 工具调用一行；只插入不更新，可审计）';
