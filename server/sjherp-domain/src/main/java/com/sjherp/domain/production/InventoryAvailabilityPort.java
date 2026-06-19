package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * 生产域库存可用量查询端口（M5-T04，只读，narrow port 范式）。
 *
 * <p>供 {@link KittingCheckService} 查询各子件当前结存，app 层用
 * {@link com.sjherp.app.config.TransactionalInventoryService} 适配。
 */
public interface InventoryAvailabilityPort {

    /**
     * 当前结存（基本单位）。无余额行返回 {@link BigDecimal#ZERO}。
     *
     * @param warehouseId 仓库 id
     * @param productId   商品 id
     * @return 结存数量（≥ 0）
     */
    BigDecimal onHand(long warehouseId, long productId);
}
