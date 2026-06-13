package com.sjherp.domain.sales;

import java.util.List;

import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 销售出库单过账所需的库存能力端口（M3-T09）。
 *
 * <p>领域 {@link SalesDeliveryService} 通过本端口调库存唯一写入口，而不直接依赖 app 层的
 * 事务包装类（领域零依赖铁律）。app 层用 {@code TransactionalInventoryService} 实现本端口：
 * 出库单各行组成一批 {@link com.sjherp.domain.inventory.OutboundCommand SALES_OUT}，
 * 同事务原子过账；execute 返回各行 {@link StockMovementResult}，出库服务由此取每行的
 * <b>COGS</b>（{@code totalCost.negate()}，移动加权出库成本）记到出库行，供 M4 利润核算。
 *
 * <p>库存不足且负库存关闭（默认）时，库存服务抛
 * {@link com.sjherp.domain.inventory.InsufficientStockException}，整批回滚（销售出库强校验库存）。
 *
 * <p>事务边界由实现方（{@code TransactionalInventoryService.execute}，@Transactional）保证，
 * 单据状态变更 + 库存过账 + 回写订单累计发货量由 app 装配的出库服务外层事务包住（拆解 §1.4）。
 */
public interface InventoryPostingPort {

    /** 批量过账（出库多行同事务原子，任一失败——含库存不足——整批回滚） */
    List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator);
}
