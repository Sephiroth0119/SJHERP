-- V16：销售订单（M3-T08，路线图 §5 销售线）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量/单价一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 销售订单走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）。
-- 下单不动库存：销售订单仅是销售约定，不产生库存流水；真正扣减发生在销售出库单过账（V17，SALES_OUT）。
-- 累计发货量 delivered_qty 记在行上，由销售出库单过账时回写（单行累计发货 ≤ 订单量）。

-- ---------------------------------------------------------------
-- 1. 销售订单头（状态机字段沿用各单据约定，doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE sales_order (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售订单主键',
    tenant_id      BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no         VARCHAR(64)     NOT NULL COMMENT '销售订单号（SO-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    customer_id    BIGINT UNSIGNED NOT NULL COMMENT '客户 id（customer.id，存在性/启用校验在入口层）',
    order_date     DATE            NOT NULL COMMENT '订单日期',
    remark         VARCHAR(255)    NULL COMMENT '订单说明，可空',
    status         VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4 红字单预留）',
    reversed_by_id VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by     VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at     DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by     VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_order_doc_no (tenant_id, doc_no),
    -- 按客户/状态分页（小企业数据量；命中最左前缀）
    KEY idx_sales_order_customer (tenant_id, customer_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '销售订单头（走 BusinessDocument 状态机；下单不动库存）';

-- ---------------------------------------------------------------
-- 2. 销售订单行（逐商品：数量 / 单价 / 金额 / 累计发货量）
-- ---------------------------------------------------------------
CREATE TABLE sales_order_line (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售订单行主键',
    tenant_id      BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    sales_order_id BIGINT UNSIGNED NOT NULL COMMENT '所属销售订单头 id（sales_order.id）',
    line_no        INT             NOT NULL COMMENT '行号（单据内从 1 起）',
    product_id     BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id）',
    quantity       DECIMAL(18, 6)  NOT NULL COMMENT '订单数量（基本单位记账，> 0）',
    unit_price     DECIMAL(18, 6)  NOT NULL COMMENT '销售单价（>=0，订单录入价，价格策略从简）',
    amount         DECIMAL(18, 2)  NOT NULL COMMENT '行金额 = 数量 × 单价（2 位 HALF_UP）',
    delivered_qty  DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '累计发货量（基本单位）；建单为 0，出库单过账回写',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_order_line (tenant_id, sales_order_id, line_no),
    KEY idx_sales_order_line_head (sales_order_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '销售订单行（数量/单价/金额/累计发货量；累计发货由出库单回写）';
