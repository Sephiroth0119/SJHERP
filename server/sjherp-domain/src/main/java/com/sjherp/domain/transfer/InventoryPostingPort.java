package com.sjherp.domain.transfer;

import java.math.BigDecimal;
import java.util.List;

import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 调拨单过账所需的库存能力端口（M3-T04）。
 *
 * <p>领域 {@link TransferService} 通过本端口调库存唯一写入口，而不直接依赖 app 层的
 * 事务包装类（领域零依赖铁律）。app 层用 {@code TransactionalInventoryService} 实现本端口：
 * 一行调拨拆成「调出腿 + 调入腿」两条 {@link StockMovementCommand} 组成一批，同事务原子过账，
 * 成本守恒（调入腿成本取调出腿原值）由库存服务的 {@code transferOutKey} 机制保证。
 *
 * <p>事务边界由实现方（{@code TransactionalInventoryService.execute}，@Transactional）保证，
 * 单据状态变更与库存过账由 app 装配的 {@code TransferService} 外层事务包住（拆解 §1.4）。
 */
public interface InventoryPostingPort {

    /** 批量过账（调拨多行的两腿组成一批，同事务原子，任一失败整体回滚） */
    List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator);

    /**
     * 按幂等键读回原流水已固化的单价（M4-T07c 调拨红冲：按原成本反向）。
     *
     * <p>调拨过账时调出/调入腿成本由库存服务按移动加权算定并落流水；红冲时须<b>按已固化的
     * 原成本反向</b>（期间可能已进新货，重算会失真，设计真源 §1.6/§75）。本方法按原两腿流水的
     * 幂等键读回其 {@code unit_cost}。流水不存在或无单价抛 {@link IllegalStateException}
     * （已过账调拨单理应有两腿流水，防御性）。
     *
     * @param idempotencyKey 原流水幂等键（{@code TRANSFER:TR号:行号:OUT|IN}）
     * @return 原流水固化单价（6 位小数）
     */
    BigDecimal originalUnitCost(String idempotencyKey);
}
