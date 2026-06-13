package com.sjherp.domain.purchase;

/**
 * 采购发票不存在领域异常（M3-T07）：按单据号查不到时抛出 → REST 404。
 */
public class PurchaseInvoiceNotFoundException extends RuntimeException {

    public PurchaseInvoiceNotFoundException(String docNo) {
        super("采购发票不存在: " + docNo);
    }
}
