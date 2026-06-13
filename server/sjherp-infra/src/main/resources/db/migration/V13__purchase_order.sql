-- V13：采购订单（M3-T05，路线图 §5 采购线）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 采购订单走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）。
-- 下单不动库存（采购订单只是对供应商的采购承诺）；到货量在采购入库单（V14）过账时回写到行 received_qty。

-- ---------------------------------------------------------------
-- 1. 采购订单头（对供应商的采购承诺；doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE purchase_order (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '采购订单主键',
    tenant_id      BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no         VARCHAR(64)     NOT NULL COMMENT '采购订单号（PO-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    supplier_id    BIGINT UNSIGNED NOT NULL COMMENT '供应商 id（supplier.id，存在性/启用校验在入口层）',
    order_date     DATE            NOT NULL COMMENT '下单日期（业务日期，可与建单系统时间不同）',
    remark         VARCHAR(255)    NULL COMMENT '采购说明，可空',
    status         VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4 预留）',
    reversed_by_id VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by     VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at     DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by     VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_order_doc_no (tenant_id, doc_no),
    -- 按供应商分页（小企业数据量；命中最左前缀）
    KEY idx_purchase_order_supplier (tenant_id, supplier_id, id),
    KEY idx_purchase_order_status (tenant_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '采购订单头（对供应商采购承诺，走 BusinessDocument 状态机；下单不动库存）';

-- ---------------------------------------------------------------
-- 2. 采购订单行（逐商品：订购数量、采购单价、行金额、累计到货量）
-- ---------------------------------------------------------------
CREATE TABLE purchase_order_line (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '采购订单行主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    purchase_order_id BIGINT UNSIGNED NOT NULL COMMENT '所属采购订单头 id（purchase_order.id）',
    line_no           INT             NOT NULL COMMENT '行号（单据内从 1 起，采购入库单引用行用此）',
    product_id        BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id）',
    quantity          DECIMAL(18, 6)  NOT NULL COMMENT '订购数量（基本单位记账，> 0）',
    unit_price        DECIMAL(18, 6)  NOT NULL COMMENT '采购单价（≥0）',
    amount            DECIMAL(18, 2)  NOT NULL COMMENT '行金额（= 数量 × 单价，2 位 HALF_UP）',
    received_qty      DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '累计已到货数量（基本单位，采购入库过账时累加，永不超过 quantity）',
    PRIMARY KEY (id),
    -- 同单内行号唯一（聚合一致性）
    UNIQUE KEY uk_purchase_order_line (tenant_id, purchase_order_id, line_no),
    -- 按头 id 装配整聚合（按行号有序读取）
    KEY idx_purchase_order_line_head (purchase_order_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '采购订单行（逐商品订购数量/单价/金额 + 累计到货量，用于部分收货跟踪）';
