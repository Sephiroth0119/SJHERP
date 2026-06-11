package com.sjherp.app.partner;

import java.util.Locale;

import com.sjherp.domain.partner.SettlementMethod;

/**
 * partner API 公共常量与解析工具。
 */
final class PartnerApiSupport {

    /**
     * 当前操作人（审计字段 created_by/updated_by 的来源）。
     *
     * <p>TODO（M2-T05 用户/认证落地后）：替换为从登录态解析的真实 userId，
     * Agent 操作则记 Agent 标识。当前无认证体系，统一记 admin（约定同 CatalogApiSupport）。
     */
    static final String OPERATOR = "admin";

    private PartnerApiSupport() {
    }

    /** 结算方式解析（非法值给出友好 400 信息，不透出枚举内部异常） */
    static SettlementMethod parseSettlementMethod(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("结算方式不能为空（MONTHLY 月结 / CASH 现结 / PREPAID 预付）");
        }
        try {
            return SettlementMethod.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("结算方式仅支持 MONTHLY / CASH / PREPAID: " + value);
        }
    }
}
