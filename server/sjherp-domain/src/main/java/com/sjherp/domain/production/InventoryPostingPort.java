package com.sjherp.domain.production;

import java.util.List;

import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 生产域库存过账端口（M5-T04，narrow port 范式）。
 *
 * <p>生产域的唯一库存写入口：领料（PRODUCTION_ISSUE）与退料（PRODUCTION_RETURN）
 * 均经此端口过账，app 层用 {@link com.sjherp.app.config.TransactionalInventoryService} 适配。
 * 生产域与库存域零直接依赖（CLAUDE.md 领域独立原则）。
 */
public interface InventoryPostingPort {

    /**
     * 批量执行库存流水（整批同一事务，任一条失败则整批回滚）。
     *
     * @param batch    库存命令批次（InboundCommand / OutboundCommand）
     * @param operator 操作人（审计）
     * @return 各命令的执行结果（顺序与 batch 一致）
     */
    List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator);
}
