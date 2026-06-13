package com.sjherp.domain.gl;

/**
 * 会计科目不存在领域异常（M4-T01）：按编码查不到时抛出 → REST 404。
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String code) {
        super("会计科目不存在: " + code);
    }
}
