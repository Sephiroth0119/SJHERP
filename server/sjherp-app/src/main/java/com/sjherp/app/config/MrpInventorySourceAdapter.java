package com.sjherp.app.config;

import java.math.BigDecimal;

import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.production.MrpInventorySource;

/**
 * {@link MrpInventorySource} 适配器（M5-T02，app 层跨域桥接）。
 *
 * <p>生产域的 {@code MrpInventorySource} 端口要求按仓库+商品查库存结存；
 * 库存域的 {@link TransactionalInventoryService#balanceOf(long, long)} 提供该能力，
 * 但两域不能直接依赖——本类在 app 层组合两者，作为接缝隔离。
 *
 * <p>商品无库存记录时 {@link TransactionalInventoryService#balanceOf} 返回零余额视图，
 * quantity() == 0，MRP 净需求计算接受。
 */
public class MrpInventorySourceAdapter implements MrpInventorySource {

    private final TransactionalInventoryService inventoryService;

    public MrpInventorySourceAdapter(TransactionalInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public BigDecimal onHand(long warehouseId, long productId) {
        InventoryBalanceView view = inventoryService.balanceOf(warehouseId, productId);
        return view.quantity();
    }
}
