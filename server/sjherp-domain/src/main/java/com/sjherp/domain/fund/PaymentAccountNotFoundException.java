package com.sjherp.domain.fund;

/**
 * 资金账户档案不存在异常（按 id 找不到资金账户时抛出，API 层映射为 404）。
 */
public class PaymentAccountNotFoundException extends RuntimeException {

    public PaymentAccountNotFoundException(String message) {
        super(message);
    }

    public static PaymentAccountNotFoundException account(long id) {
        return new PaymentAccountNotFoundException("资金账户不存在: id=" + id);
    }
}
