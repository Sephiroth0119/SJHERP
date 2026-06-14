-- V25：BOM 物料清单 + 工艺路线（M5-T01 生产基础数据）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0（与全库一致，非 UNSIGNED），纳入唯一键最左前缀；
-- 应用层暂不读写该列（恒为 0），隔离逻辑留待 SaaS 化时在持久层引入。
-- 原则：不可物理删除（只可启用/禁用），无 deleted_at/is_deleted 列。
-- 生成列 active_flag：ENABLED 时 = product_id，DISABLED 时 = NULL；
--   配合唯一索引 uk_bom_active(tenant_id, active_flag) 保证同租户同产品至多一条 ENABLED BOM/工艺路线，
--   DISABLED 行 active_flag=NULL 不触发唯一冲突（MySQL NULL 不参与唯一约束比较）。

-- ---------------------------------------------------------------
-- BOM 头
-- ---------------------------------------------------------------
CREATE TABLE bom (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT           COMMENT 'BOM 主键',
    tenant_id       BIGINT          NOT NULL DEFAULT 0               COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    product_id      BIGINT UNSIGNED NOT NULL                         COMMENT '产出品 product.id（parent）',
    version         INT             NOT NULL                         COMMENT 'BOM 版本号（同产品多版，从 1 开始递增）',
    status          VARCHAR(16)     NOT NULL                         COMMENT '档案状态：ENABLED / DISABLED',
    remark          VARCHAR(500)    NULL                             COMMENT '备注',
    -- 生成列：ENABLED 时携带 product_id，使唯一索引能约束「同产品只能一条 ENABLED」
    -- DISABLED 行为 NULL，不触发唯一冲突（MySQL NULL ≠ NULL in unique）
    active_flag     BIGINT UNSIGNED GENERATED ALWAYS AS
                        (CASE WHEN status = 'ENABLED' THEN product_id ELSE NULL END) VIRTUAL
                                                                     COMMENT '启用标记生成列（ENABLED→product_id，DISABLED→NULL）',
    created_by      VARCHAR(64)     NOT NULL                         COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at      DATETIME(6)     NOT NULL                         COMMENT '创建时间（UTC）',
    updated_by      VARCHAR(64)     NOT NULL                         COMMENT '最后操作人（审计要求）',
    updated_at      DATETIME(6)     NOT NULL                         COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bom_product_version (tenant_id, product_id, version)    COMMENT '同产品+版本唯一',
    UNIQUE KEY uk_bom_active          (tenant_id, active_flag)            COMMENT '同产品至多一条 ENABLED',
    KEY idx_bom_product               (tenant_id, product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT 'BOM 物料清单头（M5-T01）';

-- ---------------------------------------------------------------
-- BOM 行（值对象，随头一起写入，无独立生命周期）
-- ---------------------------------------------------------------
CREATE TABLE bom_line (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT          COMMENT 'BOM 行主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0              COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    bom_id           BIGINT UNSIGNED NOT NULL                        COMMENT '所属 bom.id',
    line_no          INT             NOT NULL                        COMMENT '行号（从 1 开始，同 BOM 内顺序号）',
    child_product_id BIGINT UNSIGNED NOT NULL                        COMMENT '子件 product.id',
    quantity         DECIMAL(18,6)   NOT NULL                        COMMENT '净用量（>0，不含损耗）',
    scrap_rate       DECIMAL(8,6)    NOT NULL DEFAULT 0              COMMENT '损耗率 [0,1)，加成法：实际用量 = quantity × (1 + scrap_rate)',
    unit_id          BIGINT UNSIGNED NOT NULL                        COMMENT '计量单位 unit.id',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bom_line       (tenant_id, bom_id, line_no)       COMMENT '同 BOM 行号唯一',
    UNIQUE KEY uk_bom_line_child (tenant_id, bom_id, child_product_id) COMMENT '同 BOM 同子件不重复',
    KEY idx_bom_line_head  (bom_id),
    KEY idx_bom_line_child (child_product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT 'BOM 行（值对象，无独立生命周期）（M5-T01）';

-- ---------------------------------------------------------------
-- 工艺路线头
-- ---------------------------------------------------------------
CREATE TABLE routing (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT           COMMENT '工艺路线主键',
    tenant_id       BIGINT          NOT NULL DEFAULT 0               COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    product_id      BIGINT UNSIGNED NOT NULL                         COMMENT '产出品 product.id',
    version         INT             NOT NULL                         COMMENT '工艺路线版本号（从 1 开始递增）',
    status          VARCHAR(16)     NOT NULL                         COMMENT '档案状态：ENABLED / DISABLED',
    remark          VARCHAR(500)    NULL                             COMMENT '备注',
    active_flag     BIGINT UNSIGNED GENERATED ALWAYS AS
                        (CASE WHEN status = 'ENABLED' THEN product_id ELSE NULL END) VIRTUAL
                                                                     COMMENT '启用标记生成列（ENABLED→product_id，DISABLED→NULL）',
    created_by      VARCHAR(64)     NOT NULL                         COMMENT '创建人（用户/Agent 标识，审计要求）',
    created_at      DATETIME(6)     NOT NULL                         COMMENT '创建时间（UTC）',
    updated_by      VARCHAR(64)     NOT NULL                         COMMENT '最后操作人（审计要求）',
    updated_at      DATETIME(6)     NOT NULL                         COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_routing_product_version (tenant_id, product_id, version) COMMENT '同产品+版本唯一',
    UNIQUE KEY uk_routing_active          (tenant_id, active_flag)         COMMENT '同产品至多一条 ENABLED',
    KEY idx_routing_product               (tenant_id, product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '工艺路线头（M5-T01）';

-- ---------------------------------------------------------------
-- 工艺路线工序行（值对象，随头一起写入，无独立生命周期）
-- ---------------------------------------------------------------
CREATE TABLE routing_operation (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT           COMMENT '工序行主键',
    tenant_id       BIGINT          NOT NULL DEFAULT 0               COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    routing_id      BIGINT UNSIGNED NOT NULL                         COMMENT '所属 routing.id',
    sequence_no     INT             NOT NULL                         COMMENT '工序顺序号（同路线内唯一，从 10 开始建议步进 10）',
    operation_name  VARCHAR(200)    NOT NULL                         COMMENT '工序名称',
    standard_hours  DECIMAL(18,6)   NOT NULL                         COMMENT '单件标准工时（>0，单位：小时）',
    work_center     VARCHAR(100)    NULL                             COMMENT '工作中心（可空，T06 成本归集时按工作中心汇总）',
    cost_rate       DECIMAL(18,6)   NULL                             COMMENT '工时费率（≥0，可空；与 standard_hours 相乘得单件工费；T06 成本归集用）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_routing_op     (tenant_id, routing_id, sequence_no) COMMENT '同路线工序号唯一',
    KEY idx_routing_op_head (routing_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '工艺路线工序行（M5-T01）';
