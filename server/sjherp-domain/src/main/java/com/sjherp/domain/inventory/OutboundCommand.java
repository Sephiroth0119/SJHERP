package com.sjherp.domain.inventory;

import java.math.BigDecimal;

/**
 * 出库指令（SALES_OUT / COUNT_LOSS / TRANSFER_OUT）。
 *
 * <p>出库成本不由调用方指定：由 {@link InventoryService} 按移动加权口径计算并随
 * {@link StockMovementResult} 返回——销售出库单由此取 COGS，工单领料（M5-T04）同口径复用。
 *
 * @param quantity 出库数量（正数，基本单位）
 */
public record OutboundCommand(long warehouseId, long productId, InventoryTxnType txnType,
                              BigDecimal quantity, String srcDocType, String srcDocNo,
                              Integer srcLineNo, String idempotencyKey) implements StockMovementCommand {
}
