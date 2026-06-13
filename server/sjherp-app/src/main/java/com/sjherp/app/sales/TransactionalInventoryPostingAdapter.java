package com.sjherp.app.sales;

import java.util.List;
import java.util.Objects;

import com.sjherp.app.config.TransactionalInventoryService;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;
import com.sjherp.domain.sales.InventoryPostingPort;

/**
 * 销售出库过账库存端口的 app 实现（M3-T09）：把领域 {@link InventoryPostingPort} 转调库存唯一
 * 写入口的事务包装 {@link TransactionalInventoryService}。
 *
 * <p>{@code execute} 标的 {@link TransactionalInventoryService#execute} 是 @Transactional
 * REQUIRED——会加入 {@code SalesDeliveryAppService} 写方法开启的外层事务，使单据状态变更、
 * SALES_OUT 库存流水、订单累计发货量回写原子提交（拆解 §1.4）；出库成本（COGS）由库存服务
 * 按移动加权算出并随结果返回，出库服务回填到出库行。库存不足整批回滚（销售出库强校验库存）。
 * 本适配器只做透传，不加任何逻辑。
 */
public class TransactionalInventoryPostingAdapter implements InventoryPostingPort {

    private final TransactionalInventoryService inventoryService;

    public TransactionalInventoryPostingAdapter(TransactionalInventoryService inventoryService) {
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService 不能为空");
    }

    @Override
    public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
        return inventoryService.execute(batch, operator);
    }
}
