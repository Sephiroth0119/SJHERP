package com.sjherp.domain.transfer;

import java.math.BigDecimal;

/**
 * 建单时单行的输入（M3-T04）：商品 + 调拨数量。
 *
 * @param productId 商品 id
 * @param quantity  调拨数量（基本单位，> 0）
 */
public record TransferLineInput(long productId, BigDecimal quantity) {
}
