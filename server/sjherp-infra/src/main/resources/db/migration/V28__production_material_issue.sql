-- M5-T04 领料单（MaterialIssue）+ 退料单（MaterialReturn）
-- material_issue       领料单头
-- material_issue_line  领料单行
-- material_return      退料单头
-- material_return_line 退料单行

-- 加宽库存流水 txn_type 列：原 VARCHAR(16) 容不下新增枚举 PRODUCTION_RETURN（17 字符）。
-- 流水类型按 name() 存字符串、无 CHECK 约束，加宽到 VARCHAR(32) 兼容现有值并为后续生产流水（PRODUCTION_IN 等）预留。
ALTER TABLE inventory_transaction MODIFY COLUMN txn_type VARCHAR(32) NOT NULL COMMENT '流水类型（按 InventoryTxnType.name() 存）';

CREATE TABLE material_issue
(
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    tenant_id        BIGINT         NOT NULL DEFAULT 0,
    doc_no           VARCHAR(64)    NOT NULL COMMENT '单号（MI- 前缀）',
    work_order_doc_no VARCHAR(64)   NOT NULL COMMENT '关联工单号',
    warehouse_id     BIGINT         NOT NULL COMMENT '领料仓库 id',
    remark           VARCHAR(500)   NULL     COMMENT '备注',
    status           VARCHAR(32)    NOT NULL DEFAULT 'DRAFT' COMMENT '单据状态',
    reversal_of_id   VARCHAR(64)    NULL     COMMENT '若本单为红字冲销单，关联原单号',
    reversed_by_id   VARCHAR(64)    NULL     COMMENT '若本单已被冲销，关联冲销单号',
    created_by       VARCHAR(64)    NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    updated_by       VARCHAR(64)    NOT NULL,
    updated_at       DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_issue_doc_no (tenant_id, doc_no),
    KEY idx_material_issue_wo (tenant_id, work_order_doc_no),
    KEY idx_material_issue_status (tenant_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '领料单头（M5-T04）';

CREATE TABLE material_issue_line
(
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT          NOT NULL DEFAULT 0,
    material_issue_id BIGINT          NOT NULL COMMENT '领料单头 id',
    line_no           INT             NOT NULL COMMENT '行号（从 1 起）',
    product_id        BIGINT          NOT NULL COMMENT '子件商品 id',
    required_qty      DECIMAL(18, 6)  NOT NULL COMMENT '应领数量（含损耗，计划量）',
    quantity          DECIMAL(18, 6)  NOT NULL COMMENT '实领数量',
    unit_id           BIGINT          NOT NULL COMMENT '计量单位 id',
    issued_cost       DECIMAL(18, 2)  NULL     COMMENT '领料成本（过账后回填，正数口径）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_issue_line (tenant_id, material_issue_id, line_no),
    KEY idx_material_issue_line_product (tenant_id, product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '领料单行（M5-T04）';

CREATE TABLE material_return
(
    id                    BIGINT         NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT         NOT NULL DEFAULT 0,
    doc_no                VARCHAR(64)    NOT NULL COMMENT '单号（MR- 前缀）',
    material_issue_doc_no VARCHAR(64)    NOT NULL COMMENT '原领料单号',
    warehouse_id          BIGINT         NOT NULL COMMENT '退料仓库 id',
    remark                VARCHAR(500)   NULL     COMMENT '备注',
    status                VARCHAR(32)    NOT NULL DEFAULT 'DRAFT' COMMENT '单据状态',
    reversal_of_id        VARCHAR(64)    NULL     COMMENT '若本单为红字冲销单，关联原单号',
    reversed_by_id        VARCHAR(64)    NULL     COMMENT '若本单已被冲销，关联冲销单号',
    created_by            VARCHAR(64)    NOT NULL,
    created_at            DATETIME(6)    NOT NULL,
    updated_by            VARCHAR(64)    NOT NULL,
    updated_at            DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_return_doc_no (tenant_id, doc_no),
    KEY idx_material_return_issue (tenant_id, material_issue_doc_no),
    KEY idx_material_return_status (tenant_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '退料单头（M5-T04）';

CREATE TABLE material_return_line
(
    id                 BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT          NOT NULL DEFAULT 0,
    material_return_id BIGINT          NOT NULL COMMENT '退料单头 id',
    line_no            INT             NOT NULL COMMENT '行号（从 1 起）',
    product_id         BIGINT          NOT NULL COMMENT '子件商品 id',
    quantity           DECIMAL(18, 6)  NOT NULL COMMENT '退料数量',
    unit_id            BIGINT          NOT NULL COMMENT '计量单位 id',
    returned_cost      DECIMAL(18, 2)  NULL     COMMENT '退料成本（过账后回填，正数口径）',
    src_issue_line_no  INT             NULL     COMMENT '原领料单行号（可选，追溯用）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_return_line (tenant_id, material_return_id, line_no),
    KEY idx_material_return_line_product (tenant_id, product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '退料单行（M5-T04）';
