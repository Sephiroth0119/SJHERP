package com.sjherp.app.config;

import java.util.List;

import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.production.InventoryPostingPort;

/**
 * 生产域库存过账端口适配器（M5-T04）。
 *
 * <p>将存货域的 {@link TransactionalInventoryService} 适配为生产域的 {@link InventoryPostingPort}，
 * 使生产域不直接依赖存货域实现（两个领域独立原则，CLAUDE.md）。
 */
public class MaterialIssueInventoryPostingAdapter implements InventoryPostingPort {

    private final TransactionalInventoryService inventoryService;

    public MaterialIssueInventoryPostingAdapter(TransactionalInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Override
    public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
        return inventoryService.execute(batch, operator);
    }
}
