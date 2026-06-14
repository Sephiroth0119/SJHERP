package com.sjherp.app.stocktake;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.inventory.InventoryTransaction;
import com.sjherp.domain.inventory.InventoryTransactionRepository;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.stocktake.InventoryPostingPort;

/**
 * 盘点过账库存端口的 app 实现（M3-T03）：把领域 {@link InventoryPostingPort} 转调库存唯一
 * 写入口的事务包装 {@link TransactionalInventoryService}。
 *
 * <p>{@code execute} 标的 {@link TransactionalInventoryService#execute} 是 @Transactional
 * REQUIRED——会加入 {@code StocktakeService} 写方法开启的外层事务，使单据状态变更与盘点流水
 * 原子提交（拆解 §1.4）。本适配器只做透传（M4-T07c 增 {@code originalUnitCost}
 * 按幂等键读回原盘盈/盘亏流水固化成本，红冲反向用）。
 */
public class TransactionalInventoryPostingAdapter implements InventoryPostingPort {

    private final TransactionalInventoryService inventoryService;
    private final InventoryTransactionRepository transactionRepository;

    public TransactionalInventoryPostingAdapter(TransactionalInventoryService inventoryService,
                                                InventoryTransactionRepository transactionRepository) {
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository 不能为空");
    }

    @Override
    public InventoryBalanceView balanceOf(long warehouseId, long productId) {
        return inventoryService.balanceOf(warehouseId, productId);
    }

    @Override
    public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
        return inventoryService.execute(batch, operator);
    }

    @Override
    public BigDecimal originalUnitCost(String idempotencyKey) {
        InventoryTransaction txn = transactionRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "找不到原盘点流水（幂等键 " + idempotencyKey + "），无法按原成本红冲"));
        BigDecimal unitCost = txn.getUnitCost();
        if (unitCost == null) {
            throw new IllegalStateException(
                    "原盘点流水（幂等键 " + idempotencyKey + "）无单价，无法按原成本红冲");
        }
        return unitCost;
    }
}
