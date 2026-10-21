package com.sjherp.app.chat;

/**
 * 重命名会话请求体：{"title": "..."}。
 */
public record RenameSessionRequest(String title) {
}
