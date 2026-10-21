package com.sjherp.app.chat;

/**
 * 会话已归档（CLOSED），不再接受新消息（API 返回 409 {"error": "..."}）。
 */
public class SessionClosedException extends RuntimeException {

    public SessionClosedException(String sessionId) {
        super("会话已归档，无法继续发送消息: " + sessionId);
    }
}
