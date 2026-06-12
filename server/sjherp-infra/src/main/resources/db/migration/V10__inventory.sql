-- V10：库存台账两表（M3-T01b，拆解 docs/M3拆解-库存与成本.md §1.1/§1.2）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 批次预留（Q-2）：batch_id BIGINT NOT NULL DEFAULT 0，v1.0 恒 0 不建批次表，
-- 今天即入唯一键——未来启用批次时只加批次表 + 放开取值，无须重建唯一索引/迁移流水。
-- 唯一写入口铁律（路线图 §13）：两表只允许 InventoryService 经仓储写入，
-- 不设外键约束（仓库/商品存在性与启用校验在入口层完成，高频写路径不背 FK 开销）。

-- ---------------------------------------------------------------
-- 1. 库存余额（仓库 × 商品一行；余额真源 = quantity + cost_amount 两列，
--    加权单价是派生值不冗余存储——存两份必然漂移，对账只认这两列）
-- ---------------------------------------------------------------
CREATE TABLE inventory_balance (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '余额行主键',
    tenant_id    BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '仓库 id（warehouse.id，存在性校验在入口层）',
    product_id   BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id，存在性校验在入口层）',
    batch_id     BIGINT          NOT NULL DEFAULT 0 COMMENT '批次 id（Q-2 预留，v1.0 恒 0；启用批次免重建唯一索引）',
    quantity     DECIMAL(18, 6)  NOT NULL COMMENT '结存数量（基本单位记账，多单位换算在单据行层完成——防双重换算）',
    cost_amount  DECIMAL(18, 2)  NOT NULL COMMENT '结存金额（移动加权真源；负库存关闭时由出空清零规则保证 ≥ 0）',
    updated_by   VARCHAR(64)     NOT NULL COMMENT '最后过账操作人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    updated_at   DATETIME(6)     NOT NULL COMMENT '最后过账时间（UTC）',
    PRIMARY KEY (id),
    -- 过账路径 SELECT ... FOR UPDATE 即按本唯一键定位加锁（拆解 §1.4）
    UNIQUE KEY uk_inventory_balance_dim (tenant_id, warehouse_id, product_id, batch_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '库存余额（唯一写入口 InventoryService；对账恒等式：Σ流水 = 本表两列）';

-- ---------------------------------------------------------------
-- 2. 库存流水（只插入、不更新不删除，纠错走反向流水/红字单驱动；
--    带符号设计使对账 SQL 一行写完：SUM(quantity)=余额数量 AND SUM(total_cost)=余额金额）
-- ---------------------------------------------------------------
CREATE TABLE inventory_transaction (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '流水主键（同维度内单调递增，负库存成本退化口径按 id 倒序取最近流水）',
    tenant_id              BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    warehouse_id           BIGINT UNSIGNED NOT NULL COMMENT '仓库 id（warehouse.id）',
    product_id             BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id）',
    batch_id               BIGINT          NOT NULL DEFAULT 0 COMMENT '批次 id（Q-2 预留，v1.0 恒 0）',
    txn_type               VARCHAR(16)     NOT NULL COMMENT '流水类型：OPENING/PURCHASE_IN/SALES_OUT/COUNT_GAIN/COUNT_LOSS/TRANSFER_IN/TRANSFER_OUT/COST_ADJUST',
    quantity               DECIMAL(18, 6)  NOT NULL COMMENT '带符号数量（入库正/出库负/成本调整 0，符号与类型方向由服务强制一致）',
    unit_cost              DECIMAL(18, 6)  NULL COMMENT '单价快照：入库=入库单价；出库=出库时点加权单价（仅时点快照不参与后续计算）；成本调整=NULL',
    total_cost             DECIMAL(18, 2)  NOT NULL COMMENT '带符号金额（与 quantity 同号；成本调整=调整额可正可负）；余额用本列已舍入值扣减——对账恒等式前提',
    balance_quantity_after DECIMAL(18, 6)  NOT NULL COMMENT '过账后结存数量快照（对账与排错利器）',
    balance_amount_after   DECIMAL(18, 2)  NOT NULL COMMENT '过账后结存金额快照（对账与排错利器）',
    src_doc_type           VARCHAR(32)     NOT NULL COMMENT '来源单据类型（OPENING/PURCHASE_RECEIPT/SALES_DELIVERY/STOCK_COUNT/TRANSFER/COST_ADJUST 等，必填可追溯）',
    src_doc_no             VARCHAR(64)     NOT NULL COMMENT '来源单据号（必填可追溯）',
    src_line_no            INT             NULL COMMENT '来源单据行号，可空（整单一行时可不填）',
    idempotency_key        VARCHAR(200)    NOT NULL COMMENT '幂等键（约定 docType:docNo:lineNo），同键同参返回首次结果、不同参抛异常，唯一键兜底',
    operator               VARCHAR(64)     NOT NULL COMMENT '操作人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at             DATETIME(6)     NOT NULL COMMENT '过账时间（UTC）',
    PRIMARY KEY (id),
    -- 幂等兜底（拆解 §1.2/§1.3）：单据过账重试/并发重试绝不产生重复流水
    UNIQUE KEY uk_inventory_txn_idempotency (tenant_id, idempotency_key),
    -- 按仓库 × 商品查流水（id 升序即过账顺序；对账/台账明细/最近流水查询共用）
    KEY idx_inventory_txn_dim (tenant_id, warehouse_id, product_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '库存流水（只插入不更新不删除；唯一写入口 InventoryService）';
