package com.sjherp.domain.sales;

/**
 * 销售订单不存在（M3-T08）→ API 404。
 */
public class SalesOrderNotFoundException extends RuntimeException {

    public SalesOrderNotFoundException(String docNo) {
        super("销售订单不存在: " + docNo);
    }
}
