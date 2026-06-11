-- V2：商品档案（M2-T02）+ 单据编号序号表（还 M2-T01 待办）+ 既有表补 tenant_id（ADR-002 后果条款）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；数量/换算率一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：所有新表带 tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀；
-- 应用层暂不读写该列（恒为 0），隔离逻辑留待 SaaS 化时在持久层引入。

-- ---------------------------------------------------------------
-- 1. 既有表补 tenant_id（ADR-002：已有表在下一个迁移中补列）
-- ---------------------------------------------------------------
ALTER TABLE agent_session
    ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）' AFTER id;

ALTER TABLE agent_message
    ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）' AFTER id;

-- ---------------------------------------------------------------
-- 2. 单据编号序号表（SequenceProvider 数据库实现，SELECT ... FOR UPDATE 行锁递增，重启不重号）
-- ---------------------------------------------------------------
CREATE TABLE doc_sequence (
    scope_key     VARCHAR(64) NOT NULL COMMENT '序号作用域键（前缀+年月，如 SKU-202606），按作用域独立递增',
    current_value BIGINT      NOT NULL COMMENT '当前已发出的最大序号（取号 = FOR UPDATE 锁行后 +1）',
    tenant_id     BIGINT      NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    updated_at    DATETIME(6) NOT NULL COMMENT '最后取号时间（UTC）',
    PRIMARY KEY (scope_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '单据编号序号表（按作用域行锁递增，保证并发安全且重启不重号）';

-- ---------------------------------------------------------------
-- 3. 商品类目（树形，最多 3 层，层级在领域层校验并固化到 tree_level）
-- ---------------------------------------------------------------
CREATE TABLE category (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '类目主键',
    tenant_id  BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    name       VARCHAR(100)    NOT NULL COMMENT '类目名称（租户内全局唯一，小企业从简不分层级查重）',
    parent_id  BIGINT UNSIGNED NULL COMMENT '父类目 id；NULL 表示根类目',
    tree_level TINYINT         NOT NULL COMMENT '树形层级（根=1，最多 3 层，领域层校验）',
    created_by VARCHAR(64)     NOT NULL COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_tenant_name (tenant_id, name),
    KEY idx_category_parent (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '商品类目（树形档案）';

-- ---------------------------------------------------------------
-- 4. 计量单位（unit_precision = 以该单位计量时数量保留的小数位数 0-6）
-- ---------------------------------------------------------------
CREATE TABLE unit (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '单位主键',
    tenant_id      BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    name           VARCHAR(50)     NOT NULL COMMENT '单位名称（如 瓶/箱/千克），租户内唯一',
    unit_precision TINYINT         NOT NULL COMMENT '数量精度：以该单位计量时保留的小数位数（0-6）',
    created_by     VARCHAR(64)     NOT NULL COMMENT '创建人（审计要求）',
    created_at     DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by     VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_unit_tenant_name (tenant_id, name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '计量单位档案';

-- ---------------------------------------------------------------
-- 5. 商品（档案：启用/停用两态，不走单据状态机，不可物理删除）
-- ---------------------------------------------------------------
CREATE TABLE product (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品主键',
    tenant_id    BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    code         VARCHAR(50)     NOT NULL COMMENT '商品编码（手填或 SKU-年月-序号 自动编号），租户内唯一',
    name         VARCHAR(200)    NOT NULL COMMENT '商品名称',
    spec         VARCHAR(200)    NULL COMMENT '规格型号（如 500ml）',
    category_id  BIGINT UNSIGNED NULL COMMENT '所属类目 id，可空',
    base_unit_id BIGINT UNSIGNED NOT NULL COMMENT '基本单位 id（库存与成本核算的计量基准）',
    barcode      VARCHAR(64)     NULL COMMENT '条码，可空',
    status       VARCHAR(16)     NOT NULL COMMENT '档案状态：ENABLED / DISABLED（启停，非单据状态机）',
    remark       VARCHAR(500)    NULL COMMENT '备注',
    created_by   VARCHAR(64)     NOT NULL COMMENT '创建人（审计要求）',
    created_at   DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by   VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at   DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_tenant_code (tenant_id, code),
    -- 关键字检索（LIKE 前缀可用；中缀匹配小企业数据量可接受）
    KEY idx_product_name (name),
    KEY idx_product_barcode (barcode),
    KEY idx_product_category (category_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id),
    CONSTRAINT fk_product_base_unit FOREIGN KEY (base_unit_id) REFERENCES unit (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '商品档案';

-- ---------------------------------------------------------------
-- 6. 商品级多单位换算（1 换算单位 = rate 基本单位，如 1 箱 = 12 瓶）
-- ---------------------------------------------------------------
CREATE TABLE product_unit_conversion (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '换算项主键',
    tenant_id  BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '所属商品（聚合根）',
    unit_id    BIGINT UNSIGNED NOT NULL COMMENT '换算单位 id（不得等于商品基本单位，领域层校验）',
    rate       DECIMAL(18, 6)  NOT NULL COMMENT '换算率：1 换算单位 = rate 基本单位（BigDecimal，必须 > 0）',
    created_at DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）；换算表随商品整体替换，审计以商品行为准',
    PRIMARY KEY (id),
    UNIQUE KEY uk_puc_tenant_product_unit (tenant_id, product_id, unit_id),
    KEY idx_puc_unit (unit_id),
    CONSTRAINT fk_puc_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_puc_unit FOREIGN KEY (unit_id) REFERENCES unit (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '商品级多单位换算';
