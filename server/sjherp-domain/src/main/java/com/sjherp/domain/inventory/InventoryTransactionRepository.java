package com.sjherp.domain.inventory;

import java.util.Optional;

/**
 * 库存流水仓储接口（端口，实现在 infra 层，M3-T01b）。
 *
 * <p>流水只插入、不更新不删除（拆解 §1.2）——本接口刻意不提供 update/delete。
 */
public interface InventoryTransactionRepository {

    /** 插入流水（落库后回填自增 id）；idempotency_key 撞数据库唯一键时由实现抛出异常兜底 */
    void save(InventoryTransaction transaction);

    /** 按幂等键读回原流水（幂等重试比对参数用，拆解 §1.3） */
    Optional<InventoryTransaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * 该仓该商品最近一笔 unit_cost 非空的流水（负库存放行时的成本退化口径，拆解 §1.5）。
     * 限定 unit_cost 非空：COST_ADJUST 流水单价为 null，无法为出库定价。
     */
    Optional<InventoryTransaction> findLatestWithUnitCost(long warehouseId, long productId);
}
