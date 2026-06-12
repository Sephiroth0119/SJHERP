-- V9：统一审计日志表（M2-T07，CLAUDE.md 原则 3：每笔业务写操作必有审计记录）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，应用层暂不读写（恒为 0）。
-- 写入方：app 层 AuditAspect（拦截领域 Service 的 @Audited 写方法）与
-- AuditDomainEventListener（DocumentStatusChangedEvent 等领域事件落审计）。
-- 注意：V8 由 M1-T05 会话摘要占用，本迁移固定使用 V9。

CREATE TABLE audit_log (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审计记录主键',
    tenant_id   BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    operator    VARCHAR(128)    NOT NULL COMMENT '操作人：人工=登录名；Agent=agent:<userId>（前缀区分）',
    action      VARCHAR(64)     NOT NULL COMMENT '动作标识（业务语义），如 product.create / customer.disable / document.status_changed',
    target_type VARCHAR(32)     NOT NULL COMMENT '目标类型：product/category/unit/customer/supplier/warehouse/user/gap/document',
    target_id   BIGINT          NULL COMMENT '目标主键（领域事件类记录可空，以 target_code 定位）',
    target_code VARCHAR(128)    NULL COMMENT '目标业务编码（商品/客户编码、登录名、缺口编号、单据号等）',
    summary     TEXT            NULL COMMENT '变更摘要：创建/删除记关键字段快照；更新记「变更前 → 变更后」（完整字段级 diff 留 TODO）',
    session_id  VARCHAR(64)     NULL COMMENT 'Agent 操作来源会话 id（agent_session.session_id）；人工 REST 操作为空',
    created_at  DATETIME(6)     NOT NULL COMMENT '记录时间（UTC）',
    PRIMARY KEY (id),
    -- 按操作人查操作轨迹（时间倒序）
    KEY idx_audit_log_operator_created (operator, created_at),
    -- 按目标查变更历史
    KEY idx_audit_log_target (target_type, target_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '统一审计日志（每笔业务写操作一行；只插入不更新，可审计）';
