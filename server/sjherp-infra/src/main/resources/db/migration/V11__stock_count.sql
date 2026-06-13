-- V11：库存盘点单（M3-T03，拆解 docs/M3拆解-库存与成本.md §1.7）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 盘点单走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）；
-- 过账时由 StockCountService 调库存唯一写入口产生 COUNT_GAIN/COUNT_LOSS 流水（流水不走状态机）。
-- 单仓盘点：单据头固定一个 warehouse_id，行项目逐商品记账面快照/实盘/差异。

-- ---------------------------------------------------------------
-- 1. 盘点单头（单仓盘点；状态机字段沿用各单据约定，doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE stock_count (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '盘点单主键',
    tenant_id     BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no        VARCHAR(64)     NOT NULL COMMENT '盘点单号（SC-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    warehouse_id  BIGINT UNSIGNED NOT NULL COMMENT '盘点仓库 id（warehouse.id，存在性/启用校验在入口层）',
    remark        VARCHAR(255)    NULL COMMENT '盘点说明，可空（如 2026 年 6 月月末盘点）',
    status        VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id VARCHAR(64)    NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4-T07 红字盘点单预留）',
    reversed_by_id VARCHAR(64)    NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by    VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at    DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by    VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at    DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_count_doc_no (tenant_id, doc_no),
    -- 按仓库/状态分页（小企业数据量；命中最左前缀）
    KEY idx_stock_count_warehouse (tenant_id, warehouse_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '库存盘点单头（单仓盘点，走 BusinessDocument 状态机）';

-- ---------------------------------------------------------------
-- 2. 盘点单行（逐商品：账面快照 / 实盘 / 录入单价；差异 = 实盘 − 账面 应用层派生）
-- ---------------------------------------------------------------
CREATE TABLE stock_count_line (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '盘点行主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    stock_count_id    BIGINT UNSIGNED NOT NULL COMMENT '所属盘点单头 id（stock_count.id）',
    line_no           INT             NOT NULL COMMENT '行号（单据内从 1 起，幂等键 STOCK_COUNT:docNo:行号 用此）',
    product_id        BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id）',
    snapshot_qty      DECIMAL(18, 6)  NOT NULL COMMENT '建单时账面快照数量（基本单位记账）——盘点对照基准',
    counted_qty       DECIMAL(18, 6)  NULL COMMENT '实盘数量（基本单位），录入前为 NULL',
    entered_unit_cost DECIMAL(18, 6)  NULL COMMENT '零库存盘盈录入单价（≥0）；非零盘盈/盘亏/无差异可空',
    PRIMARY KEY (id),
    -- 同单内行号唯一（聚合一致性）
    UNIQUE KEY uk_stock_count_line (tenant_id, stock_count_id, line_no),
    -- 按头 id 装配整聚合（按行号有序读取）
    KEY idx_stock_count_line_head (stock_count_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '库存盘点单行（账面快照/实盘/录入单价；差异由应用层派生）';
