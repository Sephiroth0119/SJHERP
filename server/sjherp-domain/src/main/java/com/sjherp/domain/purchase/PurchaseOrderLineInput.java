package com.sjherp.domain.purchase;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T05）：商品 + 订购数量 + 采购单价。
 *
 * @param productId 商品 id
 * @param quantity  订购数量（基本单位，> 0）
 * @param unitPrice 采购单价（≥0）
 */
public record PurchaseOrderLineInput(long productId, BigDecimal quantity, BigDecimal unitPrice) {
}
