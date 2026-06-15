-- M5-T03 工单（WO）
-- work_order        工单头
-- (无明细行表：当前批次工序/领料行留 T04/T05)

CREATE TABLE work_order
(
    id                 BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT          NOT NULL DEFAULT 0,
    doc_no             VARCHAR(64)     NOT NULL,
    product_id         BIGINT          NOT NULL COMMENT '生产商品 id',
    planned_qty        DECIMAL(18, 6)  NOT NULL COMMENT '计划数量',
    unit_id            BIGINT          NOT NULL COMMENT '计量单位 id',
    completed_qty      DECIMAL(18, 6)  NOT NULL DEFAULT 0.000000 COMMENT '已完工数量（预留，T05 完工入库更新）',
    bom_version        INT             NULL COMMENT 'BOM 版本号（预留）',
    routing_version    INT             NULL COMMENT '工艺路线版本号（预留）',
    warehouse_id       BIGINT          NULL COMMENT '生产仓库 id（预留）',
    mrp_run_doc_no     VARCHAR(64)     NULL COMMENT '来源 MRP 运行单号（MRP_SUGGESTION 来源时有值）',
    source_type        VARCHAR(32)     NOT NULL COMMENT 'MANUAL / MRP_SUGGESTION',
    planned_start_date DATE            NULL COMMENT '计划开始日期（预留）',
    planned_end_date   DATE            NULL COMMENT '计划结束日期（预留）',
    remark             VARCHAR(500)    NULL,
    status             VARCHAR(32)     NOT NULL DEFAULT 'DRAFT' COMMENT '工单状态',
    reversal_of_id     VARCHAR(64)     NULL COMMENT '若本单为红字冲销单，关联原单号（与兄弟单据一致）',
    reversed_by_id     VARCHAR(64)     NULL COMMENT '若本单已被冲销，关联冲销标记（工单无独立红字单，记自身单号）',
    created_by         VARCHAR(64)     NOT NULL,
    created_at         DATETIME(6)     NOT NULL,
    updated_by         VARCHAR(64)     NOT NULL,
    updated_at         DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_work_order_doc_no (tenant_id, doc_no),
    KEY idx_work_order_product (tenant_id, product_id),
    KEY idx_work_order_status (tenant_id, status),
    KEY idx_work_order_mrp_run (tenant_id, mrp_run_doc_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '生产工单头（M5-T03）';
