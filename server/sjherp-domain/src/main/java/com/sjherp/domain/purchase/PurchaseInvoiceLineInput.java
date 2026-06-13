package com.sjherp.domain.purchase;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T07）：引用采购入库单行 + 开票数量 + 开票金额。
 *
 * @param receiptLineNo 引用的采购入库单行号
 * @param quantity      开票数量（基本单位，> 0，≤ 收货行已收数量）
 * @param amount        开票金额（≥0）
 */
public record PurchaseInvoiceLineInput(int receiptLineNo, BigDecimal quantity, BigDecimal amount) {
}
