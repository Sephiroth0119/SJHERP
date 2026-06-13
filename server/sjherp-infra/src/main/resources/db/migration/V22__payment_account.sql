-- V22：资金账户档案（M4-T04a 现金/银行账户 master）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0（与全库一致，非 UNSIGNED），纳入唯一键最左前缀；
-- 应用层暂不读写该列（恒为 0），隔离逻辑留待 SaaS 化时在持久层引入。
-- 范式：照 V4 warehouse 档案（启用/停用两态，不走单据状态机，不可物理删除）。

-- ---------------------------------------------------------------
-- 资金账户档案（收付款的"钱进出哪个账户"主数据，映射 GL 货币科目 1001/1002/1012）
-- ---------------------------------------------------------------
CREATE TABLE payment_account (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资金账户主键',
    tenant_id       BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    code            VARCHAR(50)     NOT NULL COMMENT '资金账户编码（手填或 FA-年月-序号 自动编号），租户内唯一',
    name            VARCHAR(200)    NOT NULL COMMENT '资金账户名称',
    account_type    VARCHAR(16)     NOT NULL COMMENT '账户类别：CASH 库存现金 / BANK 银行存款 / OTHER 其他货币资金',
    gl_account_code VARCHAR(32)     NOT NULL COMMENT '映射的 GL 货币科目编码（须为已存在/启用/末级科目，如 1001/1002/1012），现金侧凭证借贷此科目',
    bank_name       VARCHAR(200)    NULL COMMENT '开户行名称，可空（BANK 账户用）',
    account_no      VARCHAR(64)     NULL COMMENT '银行账号，可空',
    status          VARCHAR(16)     NOT NULL COMMENT '档案状态：ENABLED / DISABLED（启停，非单据状态机）',
    created_by      VARCHAR(64)     NOT NULL COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at      DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by      VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at      DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_account_tenant_code (tenant_id, code),
    KEY idx_payment_account_tenant_name (tenant_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '资金账户档案（现金/银行账户，M4-T04a）';
