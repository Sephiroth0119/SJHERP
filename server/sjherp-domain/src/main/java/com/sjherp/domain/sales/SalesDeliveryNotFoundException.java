package com.sjherp.domain.sales;

/**
 * 销售出库单不存在（M3-T09）→ API 404。
 */
public class SalesDeliveryNotFoundException extends RuntimeException {

    public SalesDeliveryNotFoundException(String docNo) {
        super("销售出库单不存在: " + docNo);
    }
}
