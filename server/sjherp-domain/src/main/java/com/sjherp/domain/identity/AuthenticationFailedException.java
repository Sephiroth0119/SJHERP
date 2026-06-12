package com.sjherp.domain.identity;

/**
 * 登录认证失败（用户名/密码错误、账号停用；API 层映射为 401 {"error": "..."}）。
 *
 * <p>注意：用户名不存在与密码错误统一报"用户名或密码错误"，
 * 不向未认证调用方泄露登录名是否存在。
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
