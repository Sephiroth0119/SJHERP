package com.sjherp.domain.purchase;

/**
 * 采购入库单不存在领域异常（M3-T06）：按单据号查不到时抛出 → REST 404。
 */
public class PurchaseReceiptNotFoundException extends RuntimeException {

    public PurchaseReceiptNotFoundException(String docNo) {
        super("采购入库单不存在: " + docNo);
    }
}
