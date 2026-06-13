package com.sjherp.domain.purchase;

import java.util.List;

import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 采购入库单过账所需的库存能力端口（M3-T06）。
 *
 * <p>领域 {@link PurchaseReceiptService} 通过本端口调库存唯一写入口，而不直接依赖 app 层的
 * 事务包装类（领域零依赖铁律）。app 层用 {@code TransactionalInventoryService} 实现本端口：
 * 收货单各行组成一批 {@code PURCHASE_IN} 入库指令（unitCost = 收货单价），同事务原子过账。
 *
 * <p>事务边界由实现方（{@code TransactionalInventoryService.execute}，@Transactional）保证，
 * 单据状态变更 + 库存过账 + 采购订单到货量回写由 app 装配的 {@code PurchaseReceiptService}
 * 外层事务包住（拆解 §1.4）。
 */
public interface InventoryPostingPort {

    /** 批量过账（收货多行的 PURCHASE_IN 组成一批，同事务原子，任一失败整体回滚） */
    List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator);
}
