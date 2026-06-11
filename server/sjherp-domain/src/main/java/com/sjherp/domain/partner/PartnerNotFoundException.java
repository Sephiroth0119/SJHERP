package com.sjherp.domain.partner;

/**
 * 往来档案不存在异常（按 id 找不到客户/供应商时抛出，API 层映射为 404）。
 */
public class PartnerNotFoundException extends RuntimeException {

    public PartnerNotFoundException(String message) {
        super(message);
    }

    public static PartnerNotFoundException customer(long id) {
        return new PartnerNotFoundException("客户不存在: id=" + id);
    }

    public static PartnerNotFoundException supplier(long id) {
        return new PartnerNotFoundException("供应商不存在: id=" + id);
    }
}
