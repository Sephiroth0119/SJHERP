-- V20：业务→凭证自动化的科目与幂等约束（M4-T02，路线图 §6，依赖 V19 总账基建）
-- 约定同 V19：utf8mb4；时间列 DATETIME(6) 按 UTC；金额一律 DECIMAL(18,2)（禁止 float/double）。
-- 本迁移做两件事：
--   ① 拆 2202 应付账款为父 + 两个二级末级（暂估应付 / 正式应付），支撑采购"货到票未到"暂估模型（拆解 §1.1）；
--   ② 把 V19 非唯一 idx_voucher_source 替换为 UNIQUE KEY uk_voucher_source，从物理层兜底自动凭证幂等（拆解 §3）。

-- ---------------------------------------------------------------
-- 1. 2202 应付账款拆分（方案A：货到票未到入库挂暂估应付，发票到再冲转正式应付）
--    与 V19 的 2221→222101/222102 同构；T01 尚无凭证引用 2202，迁移安全。
-- ---------------------------------------------------------------
-- 1.1 父级 2202 改非末级（不可直接挂凭证行）
UPDATE account
SET is_leaf    = 0,
    updated_by = 'system',
    updated_at = UTC_TIMESTAMP(6)
WHERE tenant_id = 0
  AND code = '2202';

-- 1.2 二级末级科目（parent_code=2202，level=2，LIABILITY/CREDIT，预置）
INSERT INTO account (tenant_id, code, name, account_type, balance_dir, parent_code, level, is_leaf,
                     enabled, is_preset, created_by, created_at, updated_by, updated_at)
VALUES
    -- 入库时贷方：货已入但发票未到的暂估应付（余额=已收货未开票部分，全开票回零）
    (0, '220201', '应付账款—暂估应付款', 'LIABILITY', 'CREDIT', '2202', 2, 1, 1, 1,
     'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6)),
    -- 发票到时贷方：正式应付账款（与 accounts_payable 子账勾稽）
    (0, '220202', '应付账款—应付账款',   'LIABILITY', 'CREDIT', '2202', 2, 1, 1, 1,
     'system', UTC_TIMESTAMP(6), 'system', UTC_TIMESTAMP(6));

-- ---------------------------------------------------------------
-- 2. 自动凭证幂等的物理兜底：来源单据（类型 + 单号）唯一
--    V19 原索引：KEY idx_voucher_source (tenant_id, source_doc_no)（非唯一）。
--    替换为 UNIQUE KEY uk_voucher_source (tenant_id, source_doc_type, source_doc_no)——
--    同一来源单据物理不可生成第二张凭证；T01 手工凭证 source 两列 NULL，MySQL 允许多行 NULL，不冲突。
-- ---------------------------------------------------------------
ALTER TABLE voucher
    DROP INDEX idx_voucher_source,
    ADD UNIQUE KEY uk_voucher_source (tenant_id, source_doc_type, source_doc_no);
