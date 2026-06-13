package com.sjherp.domain.sales;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T08）：商品 + 订单数量 + 销售单价。
 *
 * @param productId 商品 id
 * @param quantity  订单数量（基本单位，> 0）
 * @param unitPrice 销售单价（>=0，订单录入价）
 */
public record SalesOrderLineInput(long productId, BigDecimal quantity, BigDecimal unitPrice) {
}
