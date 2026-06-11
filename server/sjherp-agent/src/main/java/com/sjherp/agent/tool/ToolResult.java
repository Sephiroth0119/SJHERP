package com.sjherp.agent.tool;

import java.util.Map;

/**
 * 工具执行结果（结构化）。
 *
 * @param success 是否成功
 * @param data    成功时的业务数据（键值对，框架序列化为 JSON 写回会话）
 * @param error   失败时的错误说明（面向 LLM，便于其向用户解释或改用其他路径；
 *                业务校验拒绝也走这里——宁可拒绝，不可破坏模型）
 */
public record ToolResult(boolean success, Map<String, Object> data, String error) {

    public static ToolResult ok(Map<String, Object> data) {
        return new ToolResult(true, data, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, Map.of(), error);
    }
}
