-- 存货分类与生产成本净额修复：
-- 商品以固定分类路由会计科目；成本结转行冻结原材料/非原材料净料费。
-- 财务历史凭证、库存流水与余额均不得通过迁移改写。

ALTER TABLE product
    ADD COLUMN inventory_category VARCHAR(32) NOT NULL DEFAULT 'MERCHANDISE'
        COMMENT '存货类别：RAW_MATERIAL / SEMI_FINISHED / FINISHED_GOOD / MERCHANDISE'
        AFTER category_id;

ALTER TABLE production_cost_settlement_line
    ADD COLUMN raw_material_cost DECIMAL(18, 2) NOT NULL DEFAULT 0.00
        COMMENT '原材料净料费（已领减已退）'
        AFTER material_cost,
    ADD COLUMN goods_material_cost DECIMAL(18, 2) NOT NULL DEFAULT 0.00
        COMMENT '非原材料净料费（已领减已退）'
        AFTER raw_material_cost;

-- 旧结转行没有历史分类快照；按原有统一 1405 口径回填，只保障回读与累计连续。
UPDATE production_cost_settlement_line
SET goods_material_cost = material_cost;
