package com.sjherp.app.chat;

import java.util.Map;

/**
 * 发消息请求体（POST /api/chat/sessions/{id}/messages），三选一：
 * <ul>
 *   <li>{@code {"text": "..."}} —— 用户自由文本；</li>
 *   <li>{@code {"optionId": "..."}} —— 点击选项卡片，只回传 id；</li>
 *   <li>{@code {"formId": "...", "values": {...}}} —— 提交表单，values 一律字符串。</li>
 * </ul>
 * 契约见 docs/选项返回协议.md「回传机制」。
 */
public record SendMessageRequest(String text, String optionId, String formId, Map<String, String> values) {
}
