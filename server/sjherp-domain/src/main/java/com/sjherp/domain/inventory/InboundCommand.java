package com.sjherp.domain.inventory;

import java.math.BigDecimal;

/**
 * 入库指令（OPENING / PURCHASE_IN / COUNT_GAIN / TRANSFER_IN）。
 *
 * @param quantity       入库数量（正数，基本单位）
 * @param unitCost       入库单价（≥0，最多 6 位小数）。
 *                       OPENING/PURCHASE_IN 必填；COUNT_GAIN 可空（有存量时默认按当前
 *                       加权单价入库，<b>零库存盘盈必须指定成本</b>）；TRANSFER_IN 必须为空
 *                       （成本取调出流水原值，金额守恒）。
 * @param transferOutKey 调拨入专用：对应调出（TRANSFER_OUT）流水的幂等键。同一批量
 *                       {@link InventoryService#execute} 内优先取本批次结果，否则查已落库流水。
 *                       非 TRANSFER_IN 禁填。
 */
public record InboundCommand(long warehouseId, long productId, InventoryTxnType txnType,
                             BigDecimal quantity, BigDecimal unitCost, String transferOutKey,
                             String srcDocType, String srcDocNo, Integer srcLineNo,
                             String idempotencyKey) implements StockMovementCommand {
}
