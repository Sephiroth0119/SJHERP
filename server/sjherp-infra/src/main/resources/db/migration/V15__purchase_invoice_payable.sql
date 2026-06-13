-- V15：采购发票与应付账款（M3-T07，路线图 §5 采购线，为 M4 应付铺路）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 采购发票走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）。
-- 三单匹配从简：发票引用采购入库单，发票行开票数量 ≤ 已收数量（服务层校验，防超额开票虚增应付）。
-- 过账生成应付账款（accounts_payable）：金额=发票总额、到期日由供应商结算方式推算、状态 OPEN（未核销）。
-- 应付为财务台账记录（不走状态机），只追加不修改（CLAUDE.md 原则 2）；核销在 M4-T03（settled_amount 预留）。

-- ---------------------------------------------------------------
-- 1. 采购发票头（登记供应商发票并与采购入库单勾稽；doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE purchase_invoice (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '采购发票主键',
    tenant_id           BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no              VARCHAR(64)     NOT NULL COMMENT '采购发票号（PINV-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    purchase_receipt_no VARCHAR(64)     NOT NULL COMMENT '引用的采购入库单号（pr.doc_no，三单匹配按其各行已收数量校验）',
    supplier_id         BIGINT UNSIGNED NOT NULL COMMENT '供应商 id（取自来源链：收货单→采购订单→供应商）',
    invoice_date        DATE            NOT NULL COMMENT '发票日期（业务日期，到期日推算基准）',
    supplier_invoice_no VARCHAR(64)     NULL COMMENT '供应商发票号（外部票据号，可空，便于对账）',
    remark              VARCHAR(255)    NULL COMMENT '发票说明，可空',
    status              VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id      VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4-T07 红字发票预留）',
    reversed_by_id      VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by          VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at          DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by          VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at          DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_invoice_doc_no (tenant_id, doc_no),
    -- 按供应商 / 引用采购入库单分页（命中最左前缀）
    KEY idx_purchase_invoice_supplier (tenant_id, supplier_id, id),
    KEY idx_purchase_invoice_pr (tenant_id, purchase_receipt_no, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '采购发票头（登记供应商发票并与采购入库单勾稽，走 BusinessDocument 状态机；过账生成应付）';

-- ---------------------------------------------------------------
-- 2. 采购发票行（逐行引用采购入库单行开票：开票数量、开票金额）
-- ---------------------------------------------------------------
CREATE TABLE purchase_invoice_line (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '采购发票行主键',
    tenant_id           BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    purchase_invoice_id BIGINT UNSIGNED NOT NULL COMMENT '所属采购发票头 id（purchase_invoice.id）',
    line_no             INT             NOT NULL COMMENT '行号（单据内从 1 起）',
    receipt_line_no     INT             NOT NULL COMMENT '引用的采购入库单行号（三单匹配按此行已收数量校验开票数量）',
    product_id          BIGINT UNSIGNED NOT NULL COMMENT '商品 id（product.id，须与收货行商品一致）',
    quantity            DECIMAL(18, 6)  NOT NULL COMMENT '开票数量（基本单位记账，> 0，≤ 收货行已收数量）',
    amount              DECIMAL(18, 2)  NOT NULL COMMENT '开票金额（≥0，2 位；容许运费/折扣/税差，本行计入应付的金额）',
    PRIMARY KEY (id),
    -- 同单内行号唯一（聚合一致性）
    UNIQUE KEY uk_purchase_invoice_line (tenant_id, purchase_invoice_id, line_no),
    -- 按头 id 装配整聚合（按行号有序读取）
    KEY idx_purchase_invoice_line_head (purchase_invoice_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '采购发票行（逐行引用采购入库单行开票；三单匹配校验开票数量 ≤ 已收数量）';

-- ---------------------------------------------------------------
-- 3. 应付账款（财务台账记录，非单据：采购发票过账时生成；只追加不修改）
-- ---------------------------------------------------------------
CREATE TABLE accounts_payable (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '应付账款主键',
    tenant_id      BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    supplier_id    BIGINT UNSIGNED NOT NULL COMMENT '供应商 id（supplier.id）',
    amount         DECIMAL(18, 2)  NOT NULL COMMENT '应付金额（> 0，2 位；本期不可改，纠错走红字冲销 M4-T07）',
    source_doc_no  VARCHAR(64)     NOT NULL COMMENT '来源单据号（采购发票号 PINV-，可追溯到收货与采购订单）',
    due_date       DATE            NOT NULL COMMENT '到期日（由供应商结算方式推算：现结/预付=发票日、月结=次月同日）',
    status         VARCHAR(16)     NOT NULL COMMENT '应付状态：OPEN（未核销）/PARTIAL/SETTLED（后两者 M4-T03 预留，本期恒 OPEN）',
    settled_amount DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '已核销金额（M4-T03 核销时累加，本期恒 0；留字段）',
    created_by     VARCHAR(64)     NOT NULL COMMENT '生成人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at     DATETIME(6)     NOT NULL COMMENT '生成时间（UTC）',
    PRIMARY KEY (id),
    -- 来源发票号唯一（一张发票过账只生成一笔应付，过账幂等防重的 DB 兜底）
    UNIQUE KEY uk_accounts_payable_source (tenant_id, source_doc_no),
    -- 按供应商 / 状态分页（命中最左前缀）
    KEY idx_accounts_payable_supplier (tenant_id, supplier_id, id),
    KEY idx_accounts_payable_status (tenant_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '应付账款（采购发票过账生成，只追加不修改；核销在 M4-T03，settled_amount 预留）';
