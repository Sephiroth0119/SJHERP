-- V12：库存调拨单（M3-T04，拆解 docs/M3拆解-库存与成本.md §1.6.5 调拨成本守恒）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 调拨单走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）；
-- 过账时由 TransferService 调库存唯一写入口产生两腿 TRANSFER_OUT/TRANSFER_IN 流水（流水不走状态机）。
-- 仓间调拨：单据头固定调出仓 from_warehouse_id 与调入仓 to_warehouse_id（二者不同），行项目逐商品记调拨数量。
-- 金额守恒：调入腿成本取调出腿原值（库存服务 transferOutKey 机制），调拨不增减企业库存价值。

-- ---------------------------------------------------------------
-- 1. 调拨单头（仓间调拨；状态机字段沿用各单据约定，doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE stock_transfer (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '调拨单主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no            VARCHAR(64)     NOT NULL COMMENT '调拨单号（TR-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    from_warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '调出仓库 id（warehouse.id，存在性/启用校验在入口层）',
    to_warehouse_id   BIGINT UNSIGNED NOT NULL COMMENT '调入仓库 id（warehouse.id，必须 ≠ 调出仓）',
    remark            VARCHAR(255)    NULL COMMENT '调拨说明，可空（如 门店补货）',
    status            VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id    VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4 红字调拨单预留）',
    reversed_by_id    VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by        VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at        DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by        VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at        DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_transfer_doc_no (tenant_id, doc_no),
    -- 按调出仓/调入仓分页（小企业数据量；命中最左前缀）
    KEY idx_stock_transfer_from (tenant_id, from_warehouse_id, id),
    KEY idx_stock_transfer_to (tenant_id, to_warehouse_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '库存调拨单头（仓间调拨，走 BusinessDocument 状态机）';

-- ---------------------------------------------------------------
-- 2. 调拨单行（逐商品：调拨数量；每行过账拆成调出腿 + 调入腿两笔库存流水）
-- ---------------------------------------------------------------
CREATE TABLE stock_transfer_line (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '调拨行主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    stock_transfer_id BIGINT UNSIGNED NOT NULL COMMENT '所属调拨单头 id（stock_transfer.id）',
    line_no           INT             NOT NULL COMMENT '行号（单据内从 1 起，幂等键 TRANSFER:docNo:行号:OUT/:IN 用此）',
    product_id        BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id）',
    quantity          DECIMAL(18, 6)  NOT NULL COMMENT '调拨数量（基本单位记账，> 0）',
    PRIMARY KEY (id),
    -- 同单内行号唯一（聚合一致性）
    UNIQUE KEY uk_stock_transfer_line (tenant_id, stock_transfer_id, line_no),
    -- 按头 id 装配整聚合（按行号有序读取）
    KEY idx_stock_transfer_line_head (stock_transfer_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '库存调拨单行（逐商品调拨数量；过账拆调出腿+调入腿两笔流水）';
