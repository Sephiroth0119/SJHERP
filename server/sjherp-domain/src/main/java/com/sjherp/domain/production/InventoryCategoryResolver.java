package com.sjherp.domain.production;

import com.sjherp.domain.catalog.InventoryCategory;

/**
 * 生产领域读取商品存货分类的只读端口。
 *
 * <p>生产成本结算只需分类结果，不直接依赖商品仓储实现，避免跨聚合写入或绕过商品领域。
 */
@FunctionalInterface
public interface InventoryCategoryResolver {

    InventoryCategory resolve(long productId);
}
