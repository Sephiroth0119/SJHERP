package com.sjherp.domain.collection;

/**
 * 收款单不存在领域异常（M4-T04b）：按单据号查不到时抛出 → REST 404。
 */
public class CollectionReceiptNotFoundException extends RuntimeException {

    public CollectionReceiptNotFoundException(String docNo) {
        super("收款单不存在: " + docNo);
    }
}
