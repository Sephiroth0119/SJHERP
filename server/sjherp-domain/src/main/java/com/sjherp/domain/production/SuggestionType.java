package com.sjherp.domain.production;

/** MRP 建议类型（M5-T02）。 */
public enum SuggestionType {
    /** 有 active BOM → 生产建议 */
    PRODUCTION,
    /** 无 active BOM（叶子）→ 采购建议 */
    PURCHASE
}
