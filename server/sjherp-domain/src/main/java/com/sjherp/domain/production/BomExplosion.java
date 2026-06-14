package com.sjherp.domain.production;

import java.math.BigDecimal;
import java.util.List;

/**
 * BOM 展开结果根对象（只读，M5-T01 explode 输出）。
 *
 * @param rootProductId 父件商品 id
 * @param rootQuantity  展开起始数量（由调用方传入）
 * @param nodes         直接子件展开节点列表（递归包含多层）
 */
public record BomExplosion(
        long rootProductId,
        BigDecimal rootQuantity,
        List<BomExplosionNode> nodes) {
}
