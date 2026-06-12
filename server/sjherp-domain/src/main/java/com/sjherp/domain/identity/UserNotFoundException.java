package com.sjherp.domain.identity;

/**
 * 用户不存在（API 层映射为 404 {"error": "..."}）。
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(long id) {
        super("用户不存在: id=" + id);
    }
}
