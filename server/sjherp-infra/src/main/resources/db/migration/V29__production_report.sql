-- M5-T05 报工单（ProductionReport）+ 报工单行（ProductionReportLine）
-- production_report       报工单头（完工数量、报废数量、完工入库成本）
-- production_report_line  报工单行（工序工时记录）
--
-- 注：work_order.completed_qty 列在 V27 已建（M5-T03），此处无需 DDL 改动。
-- 注：inventory_transaction.txn_type 在 V28 已加宽为 VARCHAR(32)，PRODUCTION_IN（12 字符）无需再改。

CREATE TABLE production_report
(
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT          NOT NULL DEFAULT 0,
    doc_no            VARCHAR(64)     NOT NULL COMMENT '单号（PR- 前缀）',
    work_order_doc_no VARCHAR(64)     NOT NULL COMMENT '关联工单号',
    warehouse_id      BIGINT          NOT NULL COMMENT '产成品入库仓库 id',
    product_id        BIGINT          NOT NULL COMMENT '生产商品 id（冗余自工单，过账入库用）',
    completed_qty     DECIMAL(18, 6)  NOT NULL COMMENT '本次合格完工入库数量（> 0）',
    scrap_qty         DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '本次报废数量（≥ 0，记录不入库）',
    unit_id           BIGINT          NOT NULL COMMENT '计量单位 id',
    inbound_cost      DECIMAL(18, 2)  NULL     COMMENT '完工入库成本（过账后回填，正数口径）',
    remark            VARCHAR(500)    NULL     COMMENT '备注',
    status            VARCHAR(32)     NOT NULL DEFAULT 'DRAFT' COMMENT '单据状态',
    reversal_of_id    VARCHAR(64)     NULL     COMMENT '若本单为红字冲销单，关联原单号',
    reversed_by_id    VARCHAR(64)     NULL     COMMENT '若本单已被冲销，关联冲销单号',
    created_by        VARCHAR(64)     NOT NULL,
    created_at        DATETIME(6)     NOT NULL,
    updated_by        VARCHAR(64)     NOT NULL,
    updated_at        DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_report_doc_no (tenant_id, doc_no),
    KEY idx_production_report_wo (tenant_id, work_order_doc_no),
    KEY idx_production_report_status (tenant_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '报工单头（M5-T05）';

CREATE TABLE production_report_line
(
    id                   BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT          NOT NULL DEFAULT 0,
    production_report_id BIGINT          NOT NULL COMMENT '报工单头 id',
    line_no              INT             NOT NULL COMMENT '行号（从 1 起）',
    operation_seq_no     INT             NULL     COMMENT '工序序号（关联工艺路线工序，可空）',
    operation_name       VARCHAR(200)    NULL     COMMENT '工序名称（可空）',
    work_center          VARCHAR(200)    NULL     COMMENT '工作中心（预留，可空）',
    reported_hours       DECIMAL(18, 6)  NOT NULL COMMENT '报工工时（小时，> 0，最多 6 位小数）',
    reported_qty         DECIMAL(18, 6)  NULL     COMMENT '该工序报工数量（可空，备查）',
    unit_id              BIGINT          NOT NULL COMMENT '工时计量单位 id',
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_report_line (tenant_id, production_report_id, line_no),
    KEY idx_production_report_line_report (tenant_id, production_report_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '报工单行（M5-T05）';
