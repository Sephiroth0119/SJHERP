-- V14：采购入库单（M3-T06，路线图 §5 采购线）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 采购入库单走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）。
-- 收货才动库存：过账（EXECUTING）时经库存唯一写入口产生 PURCHASE_IN 流水（unit_cost=收货单价），
-- 同事务回写采购订单各行到货量 received_qty。退货（负向收货）走冲销语义（M4-T07）。
-- 引用关系：每行 po_line_no 指向采购订单行（部分收货：单行收货数量 ≤ 采购订单行未收量，服务层校验）。

-- ---------------------------------------------------------------
-- 1. 采购入库单头（按采购订单收货；doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE purchase_receipt (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '采购入库单主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no            VARCHAR(64)     NOT NULL COMMENT '采购入库单号（PR-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    purchase_order_no VARCHAR(64)     NOT NULL COMMENT '引用的采购订单号（po.doc_no，收货量回写到该订单各行）',
    warehouse_id      BIGINT UNSIGNED NOT NULL COMMENT '收货仓库 id（warehouse.id，存在性/启用校验在入口层）',
    receipt_date      DATE            NOT NULL COMMENT '收货日期（业务日期）',
    remark            VARCHAR(255)    NULL COMMENT '收货说明，可空',
    status            VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id    VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4-T07 退货红字单预留）',
    reversed_by_id    VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by        VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at        DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by        VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at        DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_receipt_doc_no (tenant_id, doc_no),
    -- 按仓库 / 引用采购订单分页（命中最左前缀）
    KEY idx_purchase_receipt_warehouse (tenant_id, warehouse_id, id),
    KEY idx_purchase_receipt_po (tenant_id, purchase_order_no, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '采购入库单头（按采购订单收货，走 BusinessDocument 状态机；过账经库存唯一写入口）';

-- ---------------------------------------------------------------
-- 2. 采购入库单行（逐行引用采购订单行收货：收货数量、收货单价、入库金额）
-- ---------------------------------------------------------------
CREATE TABLE purchase_receipt_line (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '采购入库单行主键',
    tenant_id           BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    purchase_receipt_id BIGINT UNSIGNED NOT NULL COMMENT '所属采购入库单头 id（purchase_receipt.id）',
    line_no             INT             NOT NULL COMMENT '行号（单据内从 1 起，幂等键 PURCHASE_RECEIPT:docNo:行号 用此）',
    po_line_no          INT             NOT NULL COMMENT '引用的采购订单行号（收货量回写到该行 received_qty）',
    product_id          BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id，须与采购订单行商品一致）',
    quantity            DECIMAL(18, 6)  NOT NULL COMMENT '收货数量（基本单位记账，> 0，≤ 采购订单行未收量）',
    unit_cost           DECIMAL(18, 6)  NOT NULL COMMENT '收货单价（≥0，默认取采购订单行单价可改；即 PURCHASE_IN 入库单价）',
    amount              DECIMAL(18, 2)  NOT NULL COMMENT '入库金额（= 数量 × 单价，2 位 HALF_UP）',
    invoiced_qty        DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '累计已开票数量（基本单位，初始 0；采购发票过账时累加，≤ quantity；跨发票超额开票防控，防虚增应付）',
    PRIMARY KEY (id),
    -- 同单内行号唯一（聚合一致性）
    UNIQUE KEY uk_purchase_receipt_line (tenant_id, purchase_receipt_id, line_no),
    -- 按头 id 装配整聚合（按行号有序读取）
    KEY idx_purchase_receipt_line_head (purchase_receipt_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '采购入库单行（逐行引用采购订单行收货；过账组 PURCHASE_IN 入库流水并回写到货量）';
