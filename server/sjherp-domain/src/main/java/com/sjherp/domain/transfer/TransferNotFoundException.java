package com.sjherp.domain.transfer;

/**
 * 调拨单不存在领域异常（M3-T04）：按单据号查不到时抛出 → REST 404。
 */
public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(String docNo) {
        super("调拨单不存在: " + docNo);
    }
}
