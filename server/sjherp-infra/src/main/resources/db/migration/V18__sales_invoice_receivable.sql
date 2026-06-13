-- V18：销售发票与应收账款（M3-T10，路线图 §5 销售线 / §财务）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 销售发票走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）；
-- 过账时按发票金额生成一条应收账款（OPEN，未核销）。开票数量校验不超出库已发量在发票服务建单时完成。
-- 应收账款不走状态机（财务台账记录，非流转单据），状态由 status 列表示；核销（M4-T03）留字段（settled_amount）。
-- 财务记录只可冲销不可物理修改/删除（CLAUDE.md 原则 2）。

-- ---------------------------------------------------------------
-- 1. 销售发票头（引用某已过账出库单；状态机字段沿用各单据约定，doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE sales_invoice (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售发票主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no            VARCHAR(64)     NOT NULL COMMENT '发票号（SINV-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    sales_delivery_no VARCHAR(64)     NOT NULL COMMENT '引用的销售出库单号（sales_delivery.doc_no，对已发货商品开票）',
    customer_id       BIGINT UNSIGNED NOT NULL COMMENT '客户 id（冗余便于应收挂账与查询；与出库关联订单客户一致）',
    invoice_date      DATE            NOT NULL COMMENT '开票日期',
    due_date          DATE            NULL COMMENT '到期日（账期，可空，挂应收时透传）',
    remark            VARCHAR(255)    NULL COMMENT '发票说明，可空',
    status            VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id    VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4 红字发票预留）',
    reversed_by_id    VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by        VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at        DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by        VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at        DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_invoice_doc_no (tenant_id, doc_no),
    KEY idx_sales_invoice_customer (tenant_id, customer_id, id),
    KEY idx_sales_invoice_delivery (tenant_id, sales_delivery_no, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '销售发票头（引用出库单开票，走 BusinessDocument 状态机；过账生成应收）';

-- ---------------------------------------------------------------
-- 2. 销售发票行（逐行对应出库行的开票：数量 / 单价 / 金额）
-- ---------------------------------------------------------------
CREATE TABLE sales_invoice_line (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '发票行主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    sales_invoice_id  BIGINT UNSIGNED NOT NULL COMMENT '所属发票头 id（sales_invoice.id）',
    line_no           INT             NOT NULL COMMENT '发票行号（单据内从 1 起）',
    delivery_line_no  INT             NOT NULL COMMENT '关联的出库单行号（本行开票针对出库的哪一行）',
    product_id        BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id，应与出库行一致）',
    quantity          DECIMAL(18, 6)  NOT NULL COMMENT '开票数量（基本单位，> 0，≤ 出库行已发量）',
    unit_price        DECIMAL(18, 6)  NOT NULL COMMENT '开票单价（>=0，发票录入价）',
    amount            DECIMAL(18, 2)  NOT NULL COMMENT '行金额 = 数量 × 单价（2 位 HALF_UP）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_invoice_line (tenant_id, sales_invoice_id, line_no),
    KEY idx_sales_invoice_line_head (sales_invoice_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '销售发票行（开票数量/单价/金额）';

-- ---------------------------------------------------------------
-- 3. 应收账款（销售发票过账时产生；财务台账记录，不走状态机）
-- ---------------------------------------------------------------
CREATE TABLE accounts_receivable (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '应收账款主键',
    tenant_id       BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    customer_id     BIGINT UNSIGNED NOT NULL COMMENT '客户 id（customer.id）',
    amount          DECIMAL(18, 2)  NOT NULL COMMENT '应收总金额（= 销售发票金额，>=0）',
    settled_amount  DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '已核销金额（v1.0 恒 0，M4-T03 收款核销累加）',
    source_doc_no   VARCHAR(64)     NOT NULL COMMENT '来源单据号（销售发票号 SINV-xxx，可追溯成因）',
    due_date        DATE            NULL COMMENT '到期日（账期，可空——无账期即即期应收）',
    status          VARCHAR(16)     NOT NULL COMMENT '应收状态：OPEN/PARTIAL/SETTLED（v1.0 仅 OPEN，核销 M4-T03）',
    created_by      VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at      DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    -- 同来源发票号唯一：发票过账重试幂等，不重复挂账（应收服务 findBySourceDocNo 兜底）
    UNIQUE KEY uk_accounts_receivable_source (tenant_id, source_doc_no),
    -- 按客户/状态分页（小企业数据量；命中最左前缀）
    KEY idx_accounts_receivable_customer (tenant_id, customer_id, id),
    KEY idx_accounts_receivable_status (tenant_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '应收账款（销售发票过账生成；核销 M4-T03，财务记录只可冲销不可删除）';
