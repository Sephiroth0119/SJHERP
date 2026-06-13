package com.sjherp.domain.sales;

/**
 * 销售发票不存在（M3-T10）→ API 404。
 */
public class SalesInvoiceNotFoundException extends RuntimeException {

    public SalesInvoiceNotFoundException(String docNo) {
        super("销售发票不存在: " + docNo);
    }
}
