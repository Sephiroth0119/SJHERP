package com.sjherp.domain.purchase;

/**
 * 采购订单不存在领域异常（M3-T05）：按单据号查不到时抛出 → REST 404。
 */
public class PurchaseOrderNotFoundException extends RuntimeException {

    public PurchaseOrderNotFoundException(String docNo) {
        super("采购订单不存在: " + docNo);
    }
}
