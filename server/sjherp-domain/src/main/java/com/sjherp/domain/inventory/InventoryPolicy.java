package com.sjherp.domain.inventory;

/**
 * 库存策略配置对象（M3-T01a，拆解 §1.5）：app 层装配时从配置绑定。
 *
 * @param allowNegativeStock 是否允许负库存（配置项
 *        {@code sjherp.inventory.allow-negative-stock}，默认 false）。
 *        打开后仅解除数量校验，成本口径退化（见 {@link InventoryService} 出库说明）：
 *        负库存期间成本是估计值，回正后不追溯重算（v1.0 简化，检查 Agent 标红负库存行）。
 */
public record InventoryPolicy(boolean allowNegativeStock) {

    /** 默认策略：禁止负库存（出库不足抛 {@link InsufficientStockException}） */
    public static InventoryPolicy defaults() {
        return new InventoryPolicy(false);
    }
}
