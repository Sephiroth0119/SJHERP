package com.sjherp.domain.production;

import java.math.BigDecimal;

/**
 * MRP 建议行值对象（M5-T02）。
 *
 * <p>grossRequirement / onHand / netRequirement 均为基本单位数量。
 * netRequirement ≥ 0（负数截 0）。
 *
 * @param type             PRODUCTION（有 BOM）或 PURCHASE（叶子，无 BOM）
 * @param productId        商品 id
 * @param level            BOM 层级（独立需求顶层 = 0，每展开一层 +1）
 * @param grossRequirement 毛需求（基本单位）
 * @param onHand           当前结存（基本单位）
 * @param netRequirement   净需求 = max(毛需求 - 结存, 0)（基本单位）
 * @param baseUnitId       商品基本单位 id
 */
public record MrpSuggestion(
        SuggestionType type,
        long productId,
        int level,
        BigDecimal grossRequirement,
        BigDecimal onHand,
        BigDecimal netRequirement,
        long baseUnitId) {
}
