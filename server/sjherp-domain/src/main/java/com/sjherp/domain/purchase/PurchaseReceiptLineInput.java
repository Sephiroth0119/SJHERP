package com.sjherp.domain.purchase;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T06）：引用采购订单行 + 收货数量 + 可选收货单价。
 *
 * @param poLineNo 引用的采购订单行号
 * @param quantity 收货数量（基本单位，> 0，≤ 采购订单行未收量）
 * @param unitCost 收货单价（可空：为空时取采购订单行单价；≥0）
 */
public record PurchaseReceiptLineInput(int poLineNo, BigDecimal quantity, BigDecimal unitCost) {
}
