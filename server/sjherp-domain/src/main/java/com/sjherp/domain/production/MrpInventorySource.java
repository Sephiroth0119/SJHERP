package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * MRP 库存来源端口（M5-T02，domain 端口）。
 *
 * <p>返回指定仓库中指定商品的当前结存数量（基本单位），对应 InventoryBalanceView.quantity()。
 * 无余额记录时返回 0。
 */
public interface MrpInventorySource {

    /**
     * 当前结存（基本单位）。无余额行返回 {@link java.math.BigDecimal#ZERO}。
     *
     * @param warehouseId 仓库 id
     * @param productId   商品 id
     * @return 结存数量（&ge; 0）
     */
    BigDecimal onHand(long warehouseId, long productId);
}
