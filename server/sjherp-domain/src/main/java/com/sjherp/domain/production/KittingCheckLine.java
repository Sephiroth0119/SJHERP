package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * 齐套检查单行结果（M5-T04，只读值对象）。
 *
 * @param productId  子件商品 id
 * @param unitId     计量单位 id
 * @param required   需求量（plannedQty × grossQuantity，含损耗）
 * @param available  当前结存量
 * @param shortage   缺料量（max(required - available, 0)；0 表示不缺料）
 */
public record KittingCheckLine(long productId, long unitId, BigDecimal required,
                                BigDecimal available, BigDecimal shortage) {
}
