package com.sjherp.domain.inventory;

import java.math.BigDecimal;

import com.sjherp.domain.common.audit.AuditTarget;

/**
 * 库存移动过账结果（M3-T01a，拆解 §1.3）：流水 id、计算出的单价/金额与过账后余额快照。
 *
 * <p>符号口径与流水一致：quantity/totalCost 入库为正、出库为负、成本调整数量 0 金额可正可负；
 * unitCost 成本调整为 null。<b>销售出库单由此取 COGS</b>（取 {@code totalCost.negate()} 即正数成本）。
 *
 * <p>流水/余额不是单据、不走 BusinessDocument；本结果实现 {@link AuditTarget}
 * 供审计切面提取目标标识与摘要（CLAUDE.md 原则 3）。
 */
public record StockMovementResult(long transactionId, long warehouseId, long productId,
                                  InventoryTxnType txnType, BigDecimal quantity, BigDecimal unitCost,
                                  BigDecimal totalCost, BigDecimal balanceQuantityAfter,
                                  BigDecimal balanceAmountAfter, String srcDocType, String srcDocNo,
                                  Integer srcLineNo, String idempotencyKey) implements AuditTarget {

    @Override
    public Long auditTargetId() {
        return transactionId;
    }

    @Override
    public String auditTargetCode() {
        // 幂等键即「docType:docNo:lineNo」，是流水最自然的业务编码
        return idempotencyKey;
    }

    @Override
    public String auditSummary() {
        return "类型=" + txnType.label() + ", 仓库=" + warehouseId + ", 商品=" + productId
                + ", 数量=" + AuditTarget.text(plain(quantity))
                + ", 单价=" + AuditTarget.text(plain(unitCost))
                + ", 金额=" + AuditTarget.text(plain(totalCost))
                + ", 结存数量=" + AuditTarget.text(plain(balanceQuantityAfter))
                + ", 结存金额=" + AuditTarget.text(plain(balanceAmountAfter))
                + ", 来源单据=" + AuditTarget.text(srcDocType) + ":" + AuditTarget.text(srcDocNo);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
