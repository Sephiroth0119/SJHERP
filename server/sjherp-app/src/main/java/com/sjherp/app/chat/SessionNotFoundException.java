package com.sjherp.app.chat;

/**
 * 会话不存在（API 返回 404 {"error": "..."}）。
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String sessionId) {
        super("会话不存在: " + sessionId);
    }
}
