package com.sjherp.domain.gl;

/**
 * 凭证不存在领域异常（M4-T01）：按单据号查不到时抛出 → REST 404。
 */
public class VoucherNotFoundException extends RuntimeException {

    public VoucherNotFoundException(String docNo) {
        super("凭证不存在: " + docNo);
    }
}
