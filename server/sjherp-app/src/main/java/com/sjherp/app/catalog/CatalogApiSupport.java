package com.sjherp.app.catalog;

/**
 * catalog API 公共常量。
 */
final class CatalogApiSupport {

    /**
     * 当前操作人（审计字段 created_by/updated_by 的来源）。
     *
     * <p>TODO（M2-T05 用户/认证落地后）：替换为从登录态解析的真实 userId，
     * Agent 操作则记 Agent 标识。当前无认证体系，统一记 admin。
     */
    static final String OPERATOR = "admin";

    private CatalogApiSupport() {
    }
}
