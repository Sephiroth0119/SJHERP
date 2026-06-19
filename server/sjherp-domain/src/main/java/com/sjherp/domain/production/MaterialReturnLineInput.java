package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * 退料单行输入值对象（建单时传入，M5-T04）。
 *
 * @param productId        子件商品 id
 * @param quantity         退料数量（> 0）
 * @param unitId           计量单位 id
 * @param srcIssueLineNo   原领料单行号（可选，用于精确追溯；null 表示不指定）
 */
public record MaterialReturnLineInput(long productId, BigDecimal quantity, long unitId,
                                       Integer srcIssueLineNo) {
}
