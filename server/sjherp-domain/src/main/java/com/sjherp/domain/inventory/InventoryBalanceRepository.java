package com.sjherp.domain.inventory;

import java.util.Optional;

/**
 * 库存余额仓储接口（端口，实现在 infra 层，M3-T01b）。
 *
 * <p>并发契约（拆解 §1.4）：{@link #lockForUpdate} 对应
 * {@code SELECT ... FOR UPDATE}；一个事务锁多行时，{@link InventoryService}
 * 保证按 (warehouseId, productId) 升序依次调用（防死锁约定），实现方不得重排。
 */
public interface InventoryBalanceRepository {

    /**
     * 锁定并返回余额行（行不存在时 INSERT 初始零行再锁定；撞唯一键退回锁行路径，
     * 同 JdbcSequenceProvider 模式）。必须在外层事务内调用。
     *
     * @param operator 仅用于初始零行的 updated_by
     */
    InventoryBalance lockForUpdate(long warehouseId, long productId, String operator);

    /** 保存余额行（新建时落库后回填自增 id） */
    void save(InventoryBalance balance);

    /** 只读查询（balanceOf 用，不加锁） */
    Optional<InventoryBalance> find(long warehouseId, long productId);
}
