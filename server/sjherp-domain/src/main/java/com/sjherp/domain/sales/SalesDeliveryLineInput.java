package com.sjherp.domain.sales;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T09）：关联订单行号 + 商品 + 发货数量。
 *
 * @param soLineNo  关联销售订单行号（本次发货针对订单的哪一行）
 * @param productId 商品 id（应与订单行商品一致，校验在出库服务）
 * @param quantity  发货数量（基本单位，> 0，≤ 订单行剩余可发量）
 */
public record SalesDeliveryLineInput(int soLineNo, long productId, BigDecimal quantity) {
}
