package com.sjherp.app.partner;

import java.util.Locale;

import com.sjherp.domain.partner.SettlementMethod;

/**
 * partner API 公共解析工具（操作人已改为从登录态解析：CurrentUser.operator()，M2-T05）。
 */
final class PartnerApiSupport {

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
