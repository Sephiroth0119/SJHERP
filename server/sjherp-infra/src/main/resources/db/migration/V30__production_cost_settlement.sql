-- M5-T06 生产成本归集与结转（全项目最难财务点）
-- production_cost_settlement       月末成本结转单头（PC- 前缀，按账期归集料工费 + 约当产量法分摊）
-- production_cost_settlement_line  结转单行（每工单一行：料/工/费 + 完工/在产分摊 + 防重复入账锚点）
-- production_cost_param            按账期维护的成本参数（默认人工费率 + 制造费用率，D1/R-T06-1）
--
-- 约定：utf8mb4；DATETIME(6) UTC；金额 DECIMAL(18,2) / 数量·费率 DECIMAL(18,6)（禁 float/double）；
--   tenant_id BIGINT NOT NULL DEFAULT 0 纳入唯一键最左前缀（ADR-002，应用层恒为 0）。
-- 注：不改 V19（科目 5001/5101/2211/1405/1403 全有），不新增 InventoryTxnType（COST_ADJUST 复用）。
-- 注：inventory_transaction.txn_type 在 V28 已加宽为 VARCHAR(32)，COST_ADJUST 无需再改。

CREATE TABLE production_cost_settlement
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL DEFAULT 0,
    doc_no         VARCHAR(64)  NOT NULL COMMENT '单号（PC- 前缀）',
    period         CHAR(6)      NOT NULL COMMENT '账期键 yyyyMM',
    remark         VARCHAR(500) NULL     COMMENT '备注',
    status         VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT '单据状态',
    reversal_of_id VARCHAR(64)  NULL     COMMENT '若本单为红字冲销单，关联原单号',
    reversed_by_id VARCHAR(64)  NULL     COMMENT '若本单已被冲销，关联冲销单号',
    created_by     VARCHAR(64)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_by     VARCHAR(64)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_cost_settlement_doc_no (tenant_id, doc_no),
    KEY idx_production_cost_settlement_period (tenant_id, period),
    KEY idx_production_cost_settlement_status (tenant_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '月末成本结转单头（M5-T06）';

CREATE TABLE production_cost_settlement_line
(
    id                   BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id            BIGINT          NOT NULL DEFAULT 0,
    settlement_id        BIGINT          NOT NULL COMMENT '结转单头 id',
    line_no              INT             NOT NULL COMMENT '行号（从 1 起）',
    work_order_doc_no    VARCHAR(64)     NOT NULL COMMENT '工单号（每工单一行）',
    material_cost        DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '本期料成本（Σ COMPLETED 领料 issuedCost）',
    labor_cost           DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '本期人工成本（Σ 报工工时×工序费率/默认）',
    overhead_cost        DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '本期制造费用（Σ 报工工时×制造费用率）',
    completed_qty        DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '本期完工入库量（工单累计完工量）',
    completed_cost       DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '完工应负担成本（完工料+完工工费）',
    wip_qty              DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '期末在产数量',
    wip_completion_pct   DECIMAL(5, 2)   NOT NULL DEFAULT 0 COMMENT '完工程度百分比（0-100）',
    wip_cost             DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '期末在产应负担工费（料随完工结转，在产不含料）',
    already_transferred  DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '前期已结转完工工费锚点（防分批重复入账）',
    cost_adjust_idem_key VARCHAR(200)    NULL     COMMENT 'COST_ADJUST 幂等键（过账后回填）',
    voucher_doc_no       VARCHAR(64)     NULL     COMMENT 'GL 凭证号（过账后回填）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_cost_settlement_line (tenant_id, settlement_id, line_no),
    KEY idx_production_cost_settlement_line_wo (tenant_id, work_order_doc_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '月末成本结转单行（M5-T06）';

CREATE TABLE production_cost_param
(
    id                 BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id          BIGINT          NOT NULL DEFAULT 0,
    period             CHAR(6)         NOT NULL COMMENT '账期键 yyyyMM',
    default_labor_rate DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '默认人工费率（元/工时，工序无 costRate 兜底）',
    overhead_rate      DECIMAL(18, 6)  NOT NULL DEFAULT 0 COMMENT '制造费用率（元/工时，单一标准）',
    created_by         VARCHAR(64)     NOT NULL,
    created_at         DATETIME(6)     NOT NULL,
    updated_by         VARCHAR(64)     NOT NULL,
    updated_at         DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_production_cost_param (tenant_id, period)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '生产成本参数（按账期维护，M5-T06，R-T06-1 静态参数）';
