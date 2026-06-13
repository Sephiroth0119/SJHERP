package com.sjherp.domain.payable;

/**
 * 应付账款不存在（M4-T03）→ API 404。
 *
 * <p>照 {@code com.sjherp.domain.receivable.ReceivableNotFoundException} 实现。
 */
public class PayableNotFoundException extends RuntimeException {

    public PayableNotFoundException(long id) {
        super("应付账款不存在: id=" + id);
    }
}
