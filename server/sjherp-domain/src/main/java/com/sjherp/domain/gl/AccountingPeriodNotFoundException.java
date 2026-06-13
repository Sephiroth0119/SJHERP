package com.sjherp.domain.gl;

/**
 * 会计期间不存在领域异常（M4-T01）：按账期键查不到时抛出 → REST 404。
 */
public class AccountingPeriodNotFoundException extends RuntimeException {

    public AccountingPeriodNotFoundException(String period) {
        super("会计期间不存在: " + period);
    }
}
