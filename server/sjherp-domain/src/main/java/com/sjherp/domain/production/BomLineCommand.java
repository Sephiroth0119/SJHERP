package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * BOM 行命令（嵌套在 {@link BillOfMaterialsCommand} 中，由 app 层构造）。
 *
 * @param childProductId 子件商品 id
 * @param quantity       净用量（必须 &gt; 0，小数位 ≤ 6，BigDecimal）
 * @param scrapRate      损耗率（[0, 1)，小数位 ≤ 6，BigDecimal）
 * @param unitId         子件计量单位 id
 */
public record BomLineCommand(
        long childProductId,
        BigDecimal quantity,
        BigDecimal scrapRate,
        long unitId) {
}
