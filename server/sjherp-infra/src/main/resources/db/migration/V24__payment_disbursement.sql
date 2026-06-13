-- V24：付款单（M4-T04b，路线图 §6 收付款单）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 付款单走 BusinessDocument 状态机（DRAFT→APPROVED→EXECUTING→COMPLETED；DRAFT→CANCELLED）。
-- 付款单与收款单对称，是核销引擎（M4-T03）的生产触发器：过账时同事务内逐行核销应付子账 + 生成现金侧凭证
-- （借 220202 应付、贷现金/银行 glAccountCode）。核销与付现金原子同事务，任一失败整单回滚（设计真源 §2.3）。

-- ---------------------------------------------------------------
-- 1. 付款单头（向某供应商付出一笔款项；doc_no 业务唯一）
-- ---------------------------------------------------------------
CREATE TABLE payment_disbursement (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '付款单主键',
    tenant_id           BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no              VARCHAR(64)     NOT NULL COMMENT '付款单号（PAYV-年月-序号，DocumentNumberGenerator 生成），租户内唯一',
    supplier_id         BIGINT UNSIGNED NOT NULL COMMENT '供应商 id（supplier.id，与各分摊行引用的应付供应商一致）',
    payment_account_id  BIGINT UNSIGNED NOT NULL COMMENT '付出的资金账户 id（payment_account.id，过账取其 glAccountCode 作现金侧贷方科目）',
    payment_date        DATE            NOT NULL COMMENT '付款日期（业务日期，核销业务日与凭证日期基准）',
    remark              VARCHAR(255)    NULL COMMENT '付款说明，可空',
    status              VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT/APPROVED/EXECUTING/COMPLETED/CANCELLED/REVERSED',
    reversal_of_id      VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单单据号，否则 NULL（M4-T07 冲销预留）',
    reversed_by_id      VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单单据号，否则 NULL',
    created_by          VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at          DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by          VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at          DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_disbursement_doc_no (tenant_id, doc_no),
    -- 按供应商 / 资金账户分页（命中最左前缀）
    KEY idx_payment_disbursement_supplier (tenant_id, supplier_id, id),
    KEY idx_payment_disbursement_account (tenant_id, payment_account_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '付款单头（向供应商付款并分摊核销应付，走 BusinessDocument 状态机；过账核销应付+现金侧凭证）';

-- ---------------------------------------------------------------
-- 2. 付款单行（逐行把本次付款分摊到某笔应付账款：分摊金额）
-- ---------------------------------------------------------------
CREATE TABLE payment_disbursement_line (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '付款单行主键',
    tenant_id               BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    payment_disbursement_id BIGINT UNSIGNED NOT NULL COMMENT '所属付款单头 id（payment_disbursement.id）',
    line_no                 INT             NOT NULL COMMENT '行号（单据内从 1 起）',
    payable_id              BIGINT UNSIGNED NOT NULL COMMENT '分摊到的应付账款主键（accounts_payable.id）',
    allocated_amount        DECIMAL(18, 2)  NOT NULL COMMENT '分摊金额（> 0，2 位；本行冲减应付的金额）',
    PRIMARY KEY (id),
    -- 同单内行号唯一（聚合一致性）
    UNIQUE KEY uk_payment_disbursement_line (tenant_id, payment_disbursement_id, line_no),
    -- 按头 id 装配整聚合（按行号有序读取）
    KEY idx_payment_disbursement_line_head (payment_disbursement_id, line_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '付款单行（逐行分摊本次付款到某笔应付；过账经核销引擎冲减应付子账）';
