-- V6：用户与角色（M2-T05 用户/认证）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀；
-- 应用层暂不读写该列（恒为 0）。
-- 设计取舍：角色集合存 JSON 数组列（roles），不建 user_role 关联表——
-- 角色为固定枚举（ADMIN/BOSS/ACCOUNTANT/WAREHOUSE/PURCHASER/SALES）且
-- 小企业用户数量级很小，无按角色反查用户的高频查询。
-- 表名用 sys_user（避开 MySQL 关键字/内置 mysql.user 的歧义）。

CREATE TABLE sys_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键（会话 user_id、审计操作人解析的最终落点）',
    tenant_id     BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    username      VARCHAR(50)     NOT NULL COMMENT '登录名（租户内唯一，创建后不可改；审计 created_by/updated_by 记录该值）',
    display_name  VARCHAR(50)     NOT NULL COMMENT '显示名（界面展示用）',
    password_hash VARCHAR(100)    NOT NULL COMMENT '密码哈希（BCrypt，$2a$ 前缀 60 字符；绝不存明文）',
    roles         JSON            NOT NULL COMMENT '角色集合 JSON 数组，如 ["ADMIN","SALES"]（枚举见领域层 Role）',
    status        VARCHAR(16)     NOT NULL COMMENT '档案状态：ENABLED / DISABLED（停用后立即不可登录，不可物理删除）',
    created_by    VARCHAR(64)     NOT NULL COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at    DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by    VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at    DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_tenant_username (tenant_id, username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '系统用户（M2-T05）';

-- 初始管理员不再在迁移脚本中硬编码（公开仓库安全要求）。
-- 管理员账户由应用启动时从环境变量 SJHERP_ADMIN_PASSWORD 创建。
-- 如果 sys_user 表为空且环境变量未配置，应用启动会失败。
-- 参见 AdminInitializer.java。
