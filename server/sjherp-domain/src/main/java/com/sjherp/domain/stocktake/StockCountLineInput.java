package com.sjherp.domain.stocktake;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T03）：商品 + 建单账面快照 + 可选录入单价。
 *
 * <p>{@code snapshotQty} 由 app 入口层用库存 {@code balanceOf} 实时取得后填入
 * （领域层不直接读库存，保持端口最小）。{@code enteredUnitCost} 仅零库存盘盈需要。
 *
 * @param productId       商品 id
 * @param snapshotQty     建单时账面快照数量（基本单位）
 * @param enteredUnitCost 零库存盘盈录入单价（可空）
 */
public record StockCountLineInput(long productId, BigDecimal snapshotQty, BigDecimal enteredUnitCost) {
}
