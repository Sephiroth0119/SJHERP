package com.sjherp.domain.stocktake;

import java.util.List;

import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 盘点单过账所需的库存能力端口（M3-T03）。
 *
 * <p>领域 {@link StockCountService} 通过本端口调库存唯一写入口，而不直接依赖 app 层的
 * 事务包装类（领域零依赖铁律）。app 层用 {@code TransactionalInventoryService} 实现本端口：
 * <ul>
 *   <li>{@link #balanceOf}：过账阶段取当前余额，派生盘盈入库单价（账面非零时）；</li>
 *   <li>{@link #execute}：盘点多行盘盈/盘亏组成一批，同事务原子过账。</li>
 * </ul>
 *
 * <p>事务边界由实现方（{@code TransactionalInventoryService.execute}，@Transactional）保证，
 * 单据状态变更与库存过账由 app 装配的 {@code StockCountService} 外层事务包住（拆解 §1.4）。
 */
public interface InventoryPostingPort {

    /** 只读余额（无余额行返回零视图）——派生盘盈入库单价用 */
    InventoryBalanceView balanceOf(long warehouseId, long productId);

    /** 批量过账（盘点多行同事务原子，任一失败整体回滚） */
    List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator);
}
