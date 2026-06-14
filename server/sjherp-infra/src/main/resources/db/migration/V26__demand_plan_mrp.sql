-- V26: 需求计划（DemandPlan）+ MRP 运行结果（MrpRun）（M5-T02）
-- 约定：utf8mb4；时间列 DATETIME(6)，应用层一律按 UTC 读写；金额/数量一律 DECIMAL（禁止 float/double）。
-- 多租户（ADR-002）：tenant_id BIGINT NOT NULL DEFAULT 0（与全库一致），纳入唯一键最左前缀；
-- 应用层暂不读写该列（恒为 0）。
-- 原则：不可物理删除；无 deleted_at/is_deleted 列。

-- ---------------------------------------------------------------
-- 需求计划头
-- ---------------------------------------------------------------
CREATE TABLE demand_plan (
    id          BIGINT       NOT NULL AUTO_INCREMENT          COMMENT '主键',
    tenant_id   BIGINT       NOT NULL DEFAULT 0              COMMENT '租户 id（ADR-002 预留，v1.0 恒为 0）',
    doc_no      VARCHAR(40)  NOT NULL                        COMMENT '单号（DP- 前缀）',
    plan_date   DATE         NOT NULL                        COMMENT '计划日期',
    status      VARCHAR(20)  NOT NULL DEFAULT 'ENABLED'      COMMENT '档案状态：ENABLED / DISABLED',
    remark      VARCHAR(500) NULL                            COMMENT '备注',
    created_by  VARCHAR(100) NOT NULL                        COMMENT '创建人（审计要求）',
    created_at  DATETIME(6)  NOT NULL                        COMMENT '创建时间（UTC）',
    updated_by  VARCHAR(100) NOT NULL                        COMMENT '最后操作人（审计要求）',
    updated_at  DATETIME(6)  NOT NULL                        COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demand_plan_doc_no (tenant_id, doc_no)     COMMENT '同租户单号唯一'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求计划头';

-- ---------------------------------------------------------------
-- 需求计划行（值对象，先删后插，无独立生命周期）
-- ---------------------------------------------------------------
CREATE TABLE demand_plan_line (
    id               BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '行主键',
    tenant_id        BIGINT          NOT NULL DEFAULT 0              COMMENT '租户 id（ADR-002 预留）',
    demand_plan_id   BIGINT          NOT NULL                        COMMENT '需求计划头 id（FK demand_plan.id）',
    line_no          INT             NOT NULL                        COMMENT '行号（从 1 开始）',
    product_id       BIGINT          NOT NULL                        COMMENT '商品 id（FK product.id）',
    quantity         DECIMAL(18, 6)  NOT NULL                        COMMENT '需求数量（基本单位）',
    unit_id          BIGINT          NOT NULL                        COMMENT '单位 id（FK unit.id）',
    due_date         DATE            NULL                            COMMENT '需求日期（可为空表示不指定）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_demand_plan_line (tenant_id, demand_plan_id, line_no)  COMMENT '同计划行号唯一',
    INDEX idx_demand_plan_line_head    (demand_plan_id)               COMMENT '按头 id 查询行',
    INDEX idx_demand_plan_line_product (product_id)                   COMMENT '按商品查询需求'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='需求计划行';

-- ---------------------------------------------------------------
-- MRP 运行（每次运行产生一条记录；只写不改，regenerative 重跑新建记录）
-- ---------------------------------------------------------------
CREATE TABLE mrp_run (
    id                   BIGINT       NOT NULL AUTO_INCREMENT          COMMENT '主键',
    tenant_id            BIGINT       NOT NULL DEFAULT 0              COMMENT '租户 id（ADR-002 预留）',
    doc_no               VARCHAR(40)  NOT NULL                        COMMENT '运行编号（MRP- 前缀）',
    run_at               DATETIME(6)  NOT NULL                        COMMENT '运行时间（UTC）',
    warehouse_id         BIGINT       NOT NULL                        COMMENT '核算仓库 id（FK warehouse.id）',
    include_forecast     TINYINT(1)   NOT NULL DEFAULT 0              COMMENT '是否纳入预测需求：0=否，1=是',
    include_sales_order  TINYINT(1)   NOT NULL DEFAULT 0              COMMENT '是否纳入销售订单：0=否，1=是',
    remark               VARCHAR(500) NULL                            COMMENT '备注',
    created_by           VARCHAR(100) NOT NULL                        COMMENT '创建人（审计要求）',
    created_at           DATETIME(6)  NOT NULL                        COMMENT '创建时间（UTC）',
    updated_by           VARCHAR(100) NOT NULL                        COMMENT '最后操作人（审计要求）',
    updated_at           DATETIME(6)  NOT NULL                        COMMENT '最后更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mrp_run_doc_no   (tenant_id, doc_no)               COMMENT '同租户运行编号唯一',
    INDEX idx_mrp_run_warehouse    (warehouse_id)                     COMMENT '按仓库查询 MRP 历史'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MRP 运行记录头';

-- ---------------------------------------------------------------
-- MRP 建议行（附属于 mrp_run；只读，永不更新）
-- ---------------------------------------------------------------
CREATE TABLE mrp_suggestion (
    id                BIGINT          NOT NULL AUTO_INCREMENT          COMMENT '行主键',
    tenant_id         BIGINT          NOT NULL DEFAULT 0              COMMENT '租户 id（ADR-002 预留）',
    mrp_run_id        BIGINT          NOT NULL                        COMMENT 'MRP 运行 id（FK mrp_run.id）',
    line_no           INT             NOT NULL                        COMMENT '建议行号（从 1 开始）',
    suggestion_type   VARCHAR(20)     NOT NULL                        COMMENT '建议类型：PURCHASE / PRODUCTION',
    product_id        BIGINT          NOT NULL                        COMMENT '商品 id（FK product.id）',
    level             INT             NOT NULL DEFAULT 0              COMMENT 'BOM 展开层级（0 = 顶层成品）',
    gross_requirement DECIMAL(18, 6)  NOT NULL                        COMMENT '毛需求量（未扣现有库存）',
    on_hand           DECIMAL(18, 6)  NOT NULL                        COMMENT '现有可用库存量（快照）',
    net_requirement   DECIMAL(18, 6)  NOT NULL                        COMMENT '净需求量（毛需求 − 现有库存，≥ 0）',
    base_unit_id      BIGINT          NOT NULL                        COMMENT '基本单位 id（FK unit.id）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mrp_suggestion        (tenant_id, mrp_run_id, line_no)  COMMENT '同运行行号唯一',
    INDEX idx_mrp_suggestion_run        (mrp_run_id)                      COMMENT '按运行查询建议',
    INDEX idx_mrp_suggestion_product    (product_id)                      COMMENT '按商品查询建议',
    INDEX idx_mrp_suggestion_type       (suggestion_type)                 COMMENT '按建议类型筛选'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MRP 建议行';
