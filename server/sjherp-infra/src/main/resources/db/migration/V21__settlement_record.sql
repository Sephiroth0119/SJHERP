-- V21：核销记录（M4-T03 应收应付核销/账龄，路线图 §6）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0（与 V4/V15/V18 全库 tenant_id 约定一致——BIGINT 非 UNSIGNED），纳入索引最左前缀，应用层暂不读写（恒为 0）。
-- 核销记录是「对某笔应收/应付施加一次核销」的只追加流水（CLAUDE.md 原则 2/3：财务记录只可冲销不可改/删）：
--   子账（accounts_receivable / accounts_payable）的 settled_amount 是本表的维护型 rollup，本表才是核销真源。
-- payment_doc_no 由 M4-T04 收付款单回填（T03 恒 NULL）；本批不设其唯一约束——一张收付款单可核销多笔应收/应付，
--   唯一形态留 T04 收付款单驱动时再定。该列也是 M4-T07 红冲反查的锚点。

CREATE TABLE settlement_record (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '核销记录主键',
    tenant_id            BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0；与全库一致用 BIGINT 非 UNSIGNED）',
    settlement_type      VARCHAR(16)  NOT NULL COMMENT '核销类型：RECEIVABLE（应收核销）/PAYABLE（应付核销）',
    target_id            BIGINT UNSIGNED NOT NULL COMMENT 'accounts_receivable.id 或 accounts_payable.id',
    target_source_doc_no VARCHAR(64)  NOT NULL COMMENT 'AR/AP 来源单号（销售/采购发票号，追溯核销了哪笔挂账）',
    amount               DECIMAL(18,2) NOT NULL COMMENT '本次核销金额（> 0，2 位）',
    settlement_date      DATE         NOT NULL COMMENT '核销业务日',
    payment_doc_no       VARCHAR(64)  NULL COMMENT '收付款单号（M4-T04 回填；T03 恒 NULL）',
    created_by           VARCHAR(64)  NOT NULL COMMENT '创建人（人工=登录名 \ Agent=agent:<userId>，审计要求）',
    created_at           DATETIME(6)  NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    -- 按目标子账查核销历史（命中最左前缀；末尾 id 保证按发生先后稳定排序）
    KEY idx_settlement_target  (tenant_id, settlement_type, target_id, id),
    -- 按收付款单号查（M4-T04 收付款单驱动 / M4-T07 红冲反查）
    KEY idx_settlement_payment (tenant_id, payment_doc_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '核销记录（应收应付核销流水，M4-T03，只追加；子账 settled_amount 的真源）';
