-- V19：总账基建（M4-T01，路线图 §6，全系统最高风险的财务核心）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额一律 DECIMAL(18,2)（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0，纳入唯一键最左前缀，应用层暂不读写（恒为 0）。
-- 科目（account）/账期（accounting_period）为两态档案，不走单据状态机；凭证（voucher）继承 BusinessDocument。
-- 凭证过账走 DRAFT→APPROVED 一步流转（凭证过账是原子记账动作，不用 EXECUTING/COMPLETED）；
-- 借贷平衡在领域层 Voucher.create 构造时强制（不平连聚合都构造不出，到不了落库）；关账后禁止过账（VoucherService.post 守卫）。
-- 预置科目走本迁移 INSERT（参考/种子数据，幂等版本化），is_preset=1，禁停用/改类别。

-- ---------------------------------------------------------------
-- 1. 会计科目（科目表，树形自关联：parent_code 按编码；仅末级可挂凭证行）
-- ---------------------------------------------------------------
CREATE TABLE account (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '科目主键',
    tenant_id    BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    code         VARCHAR(32)     NOT NULL COMMENT '科目编码（租户内唯一，如 1001 / 222101）',
    name         VARCHAR(100)    NOT NULL COMMENT '科目名称',
    account_type VARCHAR(16)     NOT NULL COMMENT '科目类别：ASSET/LIABILITY/EQUITY/COST/PROFIT_LOSS',
    balance_dir  VARCHAR(8)      NOT NULL COMMENT '余额方向：DEBIT（借）/CREDIT（贷）',
    parent_code  VARCHAR(32)     NULL COMMENT '上级科目编码（树形自关联，按编码；一级科目为 NULL）',
    level        TINYINT         NOT NULL COMMENT '科目层级（一级=1，二级=2，……，按编码层级）',
    is_leaf      TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否末级科目（仅末级可挂凭证行）',
    enabled      TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用（停用后新凭证行不得引用，历史不受影响）',
    is_preset    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否预置科目（本迁移 INSERT，禁停用/改类别）',
    created_by   VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at   DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by   VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at   DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_code (tenant_id, code),
    -- 按上级科目查子科目（命中最左前缀）
    KEY idx_account_parent (tenant_id, parent_code),
    -- 按类别 + 编码查（科目表展示/报表归类）
    KEY idx_account_type (tenant_id, account_type, code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '会计科目（科目表，两态档案不走状态机；预置科目 is_preset=1 禁停用/改类别）';

-- ---------------------------------------------------------------
-- 2. 会计期间（账期键 period=yyyyMM；OPEN/CLOSED 两态档案，不走单据状态机）
-- ---------------------------------------------------------------
CREATE TABLE accounting_period (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '账期主键',
    tenant_id    BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    period       CHAR(6)         NOT NULL COMMENT '账期键 yyyyMM（与凭证号年月段、doc_sequence scope_key 对齐）',
    period_year  SMALLINT        NOT NULL COMMENT '冗余年份（供报表聚合）',
    period_month TINYINT         NOT NULL COMMENT '冗余月份 1-12（供报表聚合）',
    status       VARCHAR(8)      NOT NULL COMMENT '账期状态：OPEN（允许过账）/CLOSED（禁止过账）',
    closed_by    VARCHAR(64)     NULL COMMENT '关账人（CLOSED 时记录，OPEN 时为 NULL）',
    closed_at    DATETIME(6)     NULL COMMENT '关账时间（UTC，CLOSED 时记录，OPEN 时为 NULL）',
    created_by   VARCHAR(64)     NOT NULL COMMENT '创建人（审计要求）',
    created_at   DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by   VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at   DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_period (tenant_id, period)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '会计期间（账期键 yyyyMM，OPEN/CLOSED 两态档案；关账后禁止过账）';

-- ---------------------------------------------------------------
-- 3. 凭证头（继承 BusinessDocument；过账 DRAFT→APPROVED 一步，Σ借=Σ贷 落库前已校验）
-- ---------------------------------------------------------------
CREATE TABLE voucher (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '凭证头主键',
    tenant_id       BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no          VARCHAR(64)     NOT NULL COMMENT '凭证号（VCH-yyyyMM-序号，DocumentNumberGenerator 生成），租户内唯一',
    period          CHAR(6)         NOT NULL COMMENT '所属账期键 yyyyMM（凭证日期所属账期，建单时校验落在期内）',
    voucher_date    DATE            NOT NULL COMMENT '凭证日期（业务日期，须落在账期内）',
    word            VARCHAR(8)      NOT NULL DEFAULT '记' COMMENT '凭证字（默认"记"，供打印；中文不兼容 [A-Z] 前缀故单列存储）',
    total_amount    DECIMAL(18, 2)  NOT NULL COMMENT '凭证总额 = Σ借（= Σ贷，落库前已校验，2 位）',
    summary         VARCHAR(255)    NULL COMMENT '凭证摘要，可空',
    source_doc_no   VARCHAR(64)     NULL COMMENT '来源单据号（T02 自动凭证回填，T01 为 NULL；幂等防重钩子）',
    source_doc_type VARCHAR(32)     NULL COMMENT '来源单据类型（T02 自动凭证回填，T01 为 NULL）',
    status          VARCHAR(16)     NOT NULL COMMENT '单据状态：DRAFT（草稿）/APPROVED（已过账）/REVERSED（已冲销）/CANCELLED（作废）',
    reversal_of_id  VARCHAR(64)     NULL COMMENT '红字冲销单时指向被冲销原单凭证号，否则 NULL（M4-T07 红字凭证预留）',
    reversed_by_id  VARCHAR(64)     NULL COMMENT '本单被冲销后指向红字单凭证号，否则 NULL',
    created_by      VARCHAR(64)     NOT NULL COMMENT '创建人（人工=登录名 / Agent=agent:<userId>，审计要求）',
    created_at      DATETIME(6)     NOT NULL COMMENT '创建时间（UTC）',
    updated_by      VARCHAR(64)     NOT NULL COMMENT '最后操作人（审计要求）',
    updated_at      DATETIME(6)     NOT NULL COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_voucher_doc_no (tenant_id, doc_no),
    -- 按账期分页（命中最左前缀；id 倒序最近创建在前）
    KEY idx_voucher_period (tenant_id, period, id),
    -- 按来源单据号查（T02 自动凭证幂等预留）
    KEY idx_voucher_source (tenant_id, source_doc_no),
    -- 按状态分页（命中最左前缀）
    KEY idx_voucher_status (tenant_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '凭证头（继承 BusinessDocument 状态机；保存即借贷平衡，过账走 DRAFT→APPROVED 一步）';

-- ---------------------------------------------------------------
-- 4. 凭证行（逐行记借或记贷；行恰好借或贷一方 > 0，由领域层 VoucherLine.create 强制）
-- ---------------------------------------------------------------
CREATE TABLE voucher_line (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '凭证行主键',
    tenant_id    BIGINT          NOT NULL DEFAULT 0 COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    voucher_id   BIGINT UNSIGNED NOT NULL COMMENT '所属凭证头 id（voucher.id）',
    line_no      INT             NOT NULL COMMENT '行号（单据内从 1 起）',
    account_code VARCHAR(32)     NOT NULL COMMENT '挂账科目编码（须末级且启用，由 VoucherService 校验）',
    debit        DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '借方金额（非负，2 位；与 credit 恰好一方 > 0）',
    credit       DECIMAL(18, 2)  NOT NULL DEFAULT 0 COMMENT '贷方金额（非负，2 位；与 debit 恰好一方 > 0）',
    summary      VARCHAR(255)    NULL COMMENT '行摘要，可空',
    PRIMARY KEY (id),
    -- 同单内行号唯一（聚合一致性）
    UNIQUE KEY uk_voucher_line (tenant_id, voucher_id, line_no),
    -- 按头 id 装配整聚合（按行号有序读取）
    KEY idx_voucher_line_head (voucher_id, line_no),
    -- 科目余额/试算平衡派生聚合用（aggregateBalances 命中此键）
    KEY idx_voucher_line_account (tenant_id, account_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT '凭证行（逐行记借或记贷；行级不变式由领域层强制，科目余额派生用 account_code 索引）';

-- ---------------------------------------------------------------
-- 5. 预置科目表（小企业会计准则模板，is_preset=1；幂等参考数据走迁移 INSERT，拆解 §2）
--    覆盖 M3→T02 自动化所需：入库借 1403/1405 贷 2202；销售借 1122 贷 6001(+222101)；结转成本借 6401 贷 1405。
--    一级科目 2221（应交税费）is_leaf=0 不可挂账；二级 222101/222102 可挂账（parent_code=2221，level=2）。
-- ---------------------------------------------------------------
INSERT INTO account (tenant_id, code, name, account_type, balance_dir, parent_code, level, is_leaf,
                     enabled, is_preset, created_by, created_at, updated_by, updated_at)
VALUES
    -- 资产类（ASSET）
    (0, '1001', '库存现金',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1002', '银行存款',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1012', '其他货币资金',     'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1101', '短期投资',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1122', '应收账款',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1123', '预付账款',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1221', '其他应收款',       'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1401', '材料采购',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1403', '原材料',           'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1405', '库存商品',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1407', '委托加工物资',     'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1411', '周转材料',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1471', '存货跌价准备',     'ASSET',       'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1601', '固定资产',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1602', '累计折旧',         'ASSET',       'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1701', '无形资产',         'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '1801', '长期待摊费用',     'ASSET',       'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    -- 负债类（LIABILITY）
    (0, '2001', '短期借款',         'LIABILITY',   'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '2202', '应付账款',         'LIABILITY',   'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '2203', '预收账款',         'LIABILITY',   'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '2211', '应付职工薪酬',     'LIABILITY',   'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '2221', '应交税费',         'LIABILITY',   'CREDIT', NULL,   1, 0, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '222101', '应交税费—应交增值税', 'LIABILITY', 'CREDIT', '2221', 2, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '222102', '应交税费—应交所得税', 'LIABILITY', 'CREDIT', '2221', 2, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '2241', '其他应付款',       'LIABILITY',   'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '2501', '长期借款',         'LIABILITY',   'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    -- 所有者权益类（EQUITY）
    (0, '4001', '实收资本',         'EQUITY',      'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '4002', '资本公积',         'EQUITY',      'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '4101', '盈余公积',         'EQUITY',      'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '4103', '本年利润',         'EQUITY',      'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '4104', '利润分配',         'EQUITY',      'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    -- 成本类（COST）
    (0, '5001', '生产成本',         'COST',        'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '5101', '制造费用',         'COST',        'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    -- 损益类（PROFIT_LOSS）
    (0, '6001', '主营业务收入',     'PROFIT_LOSS', 'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6051', '其他业务收入',     'PROFIT_LOSS', 'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6111', '投资收益',         'PROFIT_LOSS', 'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6301', '营业外收入',       'PROFIT_LOSS', 'CREDIT', NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6401', '主营业务成本',     'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6402', '其他业务成本',     'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6403', '税金及附加',       'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6601', '销售费用',         'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6602', '管理费用',         'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6603', '财务费用',         'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6711', '营业外支出',       'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    (0, '6801', '所得税费用',       'PROFIT_LOSS', 'DEBIT',  NULL,   1, 1, 1, 1, 'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6));
