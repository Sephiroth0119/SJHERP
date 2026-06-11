package com.sjherp.domain.gap;

/**
 * 流程缺口所属业务模块。
 *
 * <p>用于缺口的归类统计与后续 Issue 化（M6-T08）时打标签；
 * 无法明确归属时用 {@link #GENERAL}。
 */
public enum BusinessModule {

    /** 采购 */
    PURCHASE,

    /** 销售 */
    SALES,

    /** 库存 */
    INVENTORY,

    /** 生产 */
    PRODUCTION,

    /** 财务 */
    FINANCE,

    /** 通用 / 无法明确归属 */
    GENERAL
}
