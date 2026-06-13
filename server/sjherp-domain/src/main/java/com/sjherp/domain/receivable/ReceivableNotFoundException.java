package com.sjherp.domain.receivable;

/**
 * 应收账款不存在（M3-T10）→ API 404。
 */
public class ReceivableNotFoundException extends RuntimeException {

    public ReceivableNotFoundException(long id) {
        super("应收账款不存在: id=" + id);
    }
}
