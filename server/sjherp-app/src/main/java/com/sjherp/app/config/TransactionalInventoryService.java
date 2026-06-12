package com.sjherp.app.config;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.inventory.CostAdjustCommand;
import com.sjherp.domain.inventory.InboundCommand;
import com.sjherp.domain.inventory.InventoryBalanceView;
import com.sjherp.domain.inventory.InventoryService;
import com.sjherp.domain.inventory.OutboundCommand;
import com.sjherp.domain.inventory.StockMovementCommand;
import com.sjherp.domain.inventory.StockMovementResult;

/**
 * 库存领域服务的事务包装（M3-T01b，拆解 §1.4 事务边界）——<b>调用方（T01c REST /
 * Agent 工具及后续单据服务）一律注入本类</b>，不要直接注入 {@link InventoryService}。
 *
 * <p><b>为什么需要包装</b>：领域层零依赖（CLAUDE.md 仓库结构铁律），
 * {@code InventoryService} 不能标注 Spring 的 {@code @Transactional}；过账又必须是
 * 跨表外层事务（锁 balance 行 → UPDATE balance + INSERT transaction 原子提交，
 * 全仓第一个跨表外层事务）。本类是 app 层的薄委托：方法级 {@code @Transactional}
 * 开事务后原样转发，不加任何业务逻辑。
 *
 * <p><b>审计路径不受影响</b>：{@code @Audited} 留在领域方法上（AuditWriteCoverageTest
 * 扫描 com.sjherp.domain 强制要求）。注入本类的 delegate 是经审计切面自动代理的
 * InventoryService Bean，调用链 = 本类事务代理（先开事务）→ 审计代理（AuditAspect）
 * → 领域方法；AuditAspect 经 TransactionAwareAuditWriter 落库，此刻事务已活动，
 * 审计延迟到 afterCommit——外层回滚零审计（D-8 修复语义，集成测试回归）。
 *
 * <p>幂等重放（同键同参直接返回原结果）也在事务内完成，只读不写，提交为空提交，无副作用。
 */
public class TransactionalInventoryService {

    private final InventoryService delegate;

    public TransactionalInventoryService(InventoryService delegate) {
        this.delegate = delegate;
    }

    /** 入库（OPENING/PURCHASE_IN/COUNT_GAIN/TRANSFER_IN）：同事务锁行 → 算成本 → 落余额与流水 */
    @Transactional
    public StockMovementResult inbound(InboundCommand command, String operator) {
        return delegate.inbound(command, operator);
    }

    /** 出库（SALES_OUT/COUNT_LOSS/TRANSFER_OUT）：成本按移动加权计算并返回（COGS 来源） */
    @Transactional
    public StockMovementResult outbound(OutboundCommand command, String operator) {
        return delegate.outbound(command, operator);
    }

    /** 成本调整（COST_ADJUST）：数量不变只调金额 */
    @Transactional
    public StockMovementResult adjustCost(CostAdjustCommand command, String operator) {
        return delegate.adjustCost(command, operator);
    }

    /** 批量过账（调拨=一出一入）：整批同事务原子，任一条失败整体回滚 */
    @Transactional
    public List<StockMovementResult> execute(List<StockMovementCommand> batch, String operator) {
        return delegate.execute(batch, operator);
    }

    /** 只读余额查询（无余额行返回零视图）；只读事务即可 */
    @Transactional(readOnly = true)
    public InventoryBalanceView balanceOf(long warehouseId, long productId) {
        return delegate.balanceOf(warehouseId, productId);
    }
}
