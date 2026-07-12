package com.sjherp.domain.catalog;

/**
 * 商品存货分类。会计科目由应用层策略统一映射，商品档案不保存可任意编辑的科目编码。
 */
public enum InventoryCategory {

    /** 未投入生产的原材料，当前映射 1403 原材料。 */
    RAW_MATERIAL,

    /** 可被上层 BOM 继续领用的自制半成品，当前映射 1405 库存商品。 */
    SEMI_FINISHED,

    /** 自制完成、可销售的产成品，当前映射 1405 库存商品。 */
    FINISHED_GOOD,

    /** 外购转售商品，当前映射 1405 库存商品。 */
    MERCHANDISE
}
