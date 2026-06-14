package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.List;

/**
 * BOM 展开树节点（只读，递归结果，M5-T01 explode 输出）。
 *
 * @param productId    子件商品 id
 * @param quantity     本节点毛需求（= 父节点量 × BomLine.grossQuantity 比率，加成法）
 * @param unitId       子件计量单位 id
 * @param level        展开层级（根=1，子=2，…）
 * @param children     下一层子节点（叶节点为空列表）
 */
public record BomExplosionNode(
        long productId,
        BigDecimal quantity,
        long unitId,
        int level,
        List<BomExplosionNode> children) {
}
