package com.sjherp.domain.payment;

/**
 * 付款单不存在领域异常（M4-T04b）：按单据号查不到时抛出 → REST 404。
 */
public class PaymentDisbursementNotFoundException extends RuntimeException {

    public PaymentDisbursementNotFoundException(String docNo) {
        super("付款单不存在: " + docNo);
    }
}
