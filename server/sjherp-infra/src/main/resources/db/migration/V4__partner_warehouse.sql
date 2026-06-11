-- V4：往来档案（M2-T03 客户/供应商）+ 仓库档案（M2-T04）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：所有新表带 tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀；
-- 应用层暂不读写该列（恒为 0），隔离逻辑留待 SaaS 化时在持久层引入。

-- ---------------------------------------------------------------
-- 1. 客户档案（启用/停用两态，不走单据状态机，不可物理删除）
-- ---------------------------------------------------------------
CREATE TABLE customer (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    code              VARCHAR(50)     NOT NULL COMMENT '客户编码（手填或 CUS-年月-序号 自动编号），租户内唯一',
    name              VARCHAR(200)    NOT NULL COMMENT '客户名称',
    contact_person    VARCHAR(64)     NULL COMMENT '联系人，可空',
    contact_phone     VARCHAR(32)     NULL COMMENT '联系电话，可空',
    address           VARCHAR(255)    NULL COMMENT '地址，可空',
    tax_no            VARCHAR(64)     NULL COMMENT '税号（纳税人识别号），可空',
    settlement_method VARCHAR(16)     NOT NULL COMMENT '结算方式：MONTHLY 月结 / CASH 现结 / PREPAID 预付',
    credit_limit      DECIMAL(18, 2)  NULL COMMENT '信用额度（BigDecimal，可空表示不设限；超限校验留 M3）',
    currency          CHAR(3)         NOT NULL DEFAULT 'CNY' COMMENT '默认币种：v1.0 固定 CNY（Q-4 决策，字段预留多币种）',
    status            VARCHAR(16)     NOT NULL COMMENT '档案状态：ENABLED / DISABLED（启停，非单据状态机）',
    created_by        VARCHAR(64)     NOT NULL COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at        DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by        VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at        DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_tenant_code (tenant_id, code),
    -- 关键字检索（LIKE 前缀可用；中缀匹配小企业数据量可接受）
    KEY idx_customer_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '客户档案';

-- ---------------------------------------------------------------
-- 2. 供应商档案
-- ---------------------------------------------------------------
CREATE TABLE supplier (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '供应商主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    code              VARCHAR(50)     NOT NULL COMMENT '供应商编码（手填或 SUP-年月-序号 自动编号），租户内唯一',
    name              VARCHAR(200)    NOT NULL COMMENT '供应商名称',
    contact_person    VARCHAR(64)     NULL COMMENT '联系人，可空',
    contact_phone     VARCHAR(32)     NULL COMMENT '联系电话，可空',
    address           VARCHAR(255)    NULL COMMENT '地址，可空',
    tax_no            VARCHAR(64)     NULL COMMENT '税号（纳税人识别号），可空',
    settlement_method VARCHAR(16)     NOT NULL COMMENT '结算方式：MONTHLY 月结 / CASH 现结 / PREPAID 预付',
    status            VARCHAR(16)     NOT NULL COMMENT '档案状态：ENABLED / DISABLED（启停，非单据状态机）',
    created_by        VARCHAR(64)     NOT NULL COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at        DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by        VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at        DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_tenant_code (tenant_id, code),
    KEY idx_supplier_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '供应商档案';

-- ---------------------------------------------------------------
-- 3. 仓库档案（小企业从简：仓库必有，库位可选——location_enabled 仅为开关，
--    库位表与库位维度库存留 M3 按需建设）
-- ---------------------------------------------------------------
CREATE TABLE warehouse (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '仓库主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    code             VARCHAR(50)     NOT NULL COMMENT '仓库编码（手填或 WH-年月-序号 自动编号），租户内唯一',
    name             VARCHAR(200)    NOT NULL COMMENT '仓库名称',
    address          VARCHAR(255)    NULL COMMENT '地址，可空',
    manager          VARCHAR(64)     NULL COMMENT '负责人，可空',
    location_enabled TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否启用库位管理（本期仅字段预留，库位表留 M3）',
    status           VARCHAR(16)     NOT NULL COMMENT '档案状态：ENABLED / DISABLED（启停，非单据状态机）',
    created_by       VARCHAR(64)     NOT NULL COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at       DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by       VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at       DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_tenant_code (tenant_id, code),
    KEY idx_warehouse_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '仓库档案';
