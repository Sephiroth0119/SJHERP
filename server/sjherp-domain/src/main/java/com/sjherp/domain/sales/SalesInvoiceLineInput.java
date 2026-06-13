package com.sjherp.domain.sales;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T10）：关联出库行号 + 商品 + 开票数量 + 单价。
 *
 * @param deliveryLineNo 关联销售出库单行号（本行开票针对出库的哪一行）
 * @param productId      商品 id（应与出库行商品一致，校验在发票服务）
 * @param quantity       开票数量（基本单位，> 0，≤ 出库行已发量）
 * @param unitPrice      开票单价（>=0，发票录入价）
 */
public record SalesInvoiceLineInput(int deliveryLineNo, long productId, BigDecimal quantity,
                                    BigDecimal unitPrice) {
}
