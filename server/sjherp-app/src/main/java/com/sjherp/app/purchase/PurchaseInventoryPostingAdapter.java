package com.sjherp.app.purchase;

import java.util.List;
import java.util.Objects;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.purchase.InventoryPostingPort;

/**
 * 采购入库过账库存端口的 app 实现（M3-T06）：把领域 {@link InventoryPostingPort} 转调库存唯一
 * 写入口的事务包装 {@link TransactionalInventoryService}（照 {@code TransactionalInventoryPostingAdapter}）。
 *
 * <p>{@code execute} 标的 {@link TransactionalInventoryService#execute} 是 @Transactional
 * REQUIRED——会加入 {@code PurchaseReceiptAppService} 写方法开启的外层事务，使单据状态变更、
 * 各行 PURCHASE_IN 入库流水与采购订单到货量回写原子提交（拆解 §1.4）。本适配器只做透传。
 */
public class PurchaseInventoryPostingAdapter implements InventoryPostingPort {

    private final TransactionalInventoryService inventoryService;

    public PurchaseInventoryPostingAdapter(TransactionalInventoryService inventoryService) {
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
    }

    @Override
    public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
        return inventoryService.execute(batch, operator);
    }
}
