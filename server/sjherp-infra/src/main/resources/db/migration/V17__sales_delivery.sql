-- V17：销售出库单（M3-T09，路线图 §5 销售线）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 出库单走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）；
-- 过账时由 SalesDeliveryService 调库存唯一写入口产生 SALES_OUT 流水（流水不走状态机），
-- 库存按移动加权扣减并算出 COGS（销货成本）回填到出库行 cogs_amount，同事务回写订单累计发货量。
-- 销售出库默认强校验库存：库存不足且负库存关闭时整批回滚。

-- ---------------------------------------------------------------
-- 1. 销售出库单头（引用某销售订单做部分发货；状态机字段沿用各单据约定，doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE sales_delivery (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售出库单主键',
    tenant_id       BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no          VARCHAR(64)     NOT NULL COMMENT '出库单号（SD-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    sales_order_no  VARCHAR(64)     NOT NULL COMMENT '引用的销售订单号（sales_order.doc_no，部分发货针对它）',
    warehouse_id    BIGINT UNSIGNED NOT NULL COMMENT '出库仓库 id（warehouse.id，存在性/启用校验在入口层）',
    remark          VARCHAR(255)    NULL COMMENT '出库说明，可空',
    status          VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id  VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4 退货/红字单预留）',
    reversed_by_id  VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by      VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at      DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by      VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at      DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_delivery_doc_no (tenant_id, doc_no),
    -- 按关联订单/仓库分页（小企业数据量；命中最左前缀）
    KEY idx_sales_delivery_order (tenant_id, sales_order_no, id),
    KEY idx_sales_delivery_warehouse (tenant_id, warehouse_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '销售出库单头（引用销售订单部分发货，走 BusinessDocument 状态机）';

-- ---------------------------------------------------------------
-- 2. 销售出库单行（逐行对应订单行的一次发货：发货数量 + 过账后回填的 COGS）
-- ---------------------------------------------------------------
CREATE TABLE sales_delivery_line (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '出库单行主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    sales_delivery_id BIGINT UNSIGNED NOT NULL COMMENT '所属出库单头 id（sales_delivery.id）',
    line_no           INT             NOT NULL COMMENT '出库行号（单据内从 1 起，幂等键 SALES_DELIVERY:docNo:行号 用此）',
    so_line_no        INT             NOT NULL COMMENT '关联的销售订单行号（本次发货针对订单的哪一行）',
    product_id        BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id，应与订单行一致）',
    quantity          DECIMAL(18, 6)  NOT NULL COMMENT '发货数量（基本单位，> 0，≤ 订单行剩余可发量）',
    cogs_amount       DECIMAL(18, 2)  NULL COMMENT '销货成本 COGS（移动加权出库成本，正数口径）；建单为 NULL，过账后回填',
    invoiced_qty      DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '累计已开票数量（基本单位，初始 0；销售发票过账时累加，≤ quantity；跨发票超额开票防控，防虚增应收）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_delivery_line (tenant_id, sales_delivery_id, line_no),
    KEY idx_sales_delivery_line_head (sales_delivery_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '销售出库单行（发货数量 + 过账回填的 COGS；COGS 供 M4 利润核算）';
