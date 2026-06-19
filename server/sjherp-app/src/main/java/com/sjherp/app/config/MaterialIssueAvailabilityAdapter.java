package com.sjherp.app.config;

import java.math.BigDecimal;

import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.production.InventoryAvailabilityPort;

/**
 * 生产域库存可用量查询端口适配器（M5-T04）。
 *
 * <p>将 {@link TransactionalInventoryService#balanceOf} 适配为生产域的只读端口，
 * 供 KittingCheckService 查询各子件当前结存（不写库存）。
 *
 * <p>商品无库存记录时 {@link TransactionalInventoryService#balanceOf} 返回零余额视图，
 * quantity() == 0，KittingCheckService 直接使用。
 */
public class MaterialIssueAvailabilityAdapter implements InventoryAvailabilityPort {

    private final TransactionalInventoryService inventoryService;

    public MaterialIssueAvailabilityAdapter(TransactionalInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public BigDecimal onHand(long warehouseId, long productId) {
        InventoryBalanceView view = inventoryService.balanceOf(warehouseId, productId);
        return view == null ? BigDecimal.ZERO : view.quantity();
    }
}
