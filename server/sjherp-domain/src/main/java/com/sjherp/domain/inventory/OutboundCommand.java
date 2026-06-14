package com.sjherp.domain.inventory;

import java.math.BigDecimal;

/**
 * 出库指令（SALES_OUT / COUNT_LOSS / TRANSFER_OUT）。
 *
 * <p>出库成本默认不由调用方指定：由 {@link InventoryService} 按移动加权口径计算并随
 * {@link StockMovementResult} 返回——销售出库单由此取 COGS，工单领料（M5-T04）同口径复用。
 *
 * <h2>红冲按原成本出库（M4-T07b）</h2>
 * 业务单据红冲（如采购入库红冲=按原收货成本反向出库）需<b>按已固化的原单价反向</b>，而非
 * 重算移动加权（期间可能已进新货，重算会失真，设计真源 §1.6/§2 共享基元 1）。为此提供可选
 * {@link #overriddenUnitCost}：
 * <ul>
 *   <li>{@code null}（默认）：{@link InventoryService#outbound} 走原移动加权路径，行为完全不变；</li>
 *   <li>非空（≥0）：跳过加权，直接按该单价算 {@code totalCost = round2(unitCost × qty)} 出库。</li>
 * </ul>
 *
 * @param quantity           出库数量（正数，基本单位）
 * @param overriddenUnitCost 可选指定出库单价（红冲按原成本反向用，≥0；为 null 走移动加权）
 */
public record OutboundCommand(long warehouseId, long productId, InventoryTxnType txnType,
                              BigDecimal quantity, String srcDocType, String srcDocNo,
                              Integer srcLineNo, String idempotencyKey,
                              BigDecimal overriddenUnitCost) implements StockMovementCommand {

    /** 常用构造：不指定出库单价（走移动加权，原 8 参签名的兼容入口）。 */
    public OutboundCommand(long warehouseId, long productId, InventoryTxnType txnType,
                           BigDecimal quantity, String srcDocType, String srcDocNo,
                           Integer srcLineNo, String idempotencyKey) {
        this(warehouseId, productId, txnType, quantity, srcDocType, srcDocNo, srcLineNo,
                idempotencyKey, null);
    }
}
