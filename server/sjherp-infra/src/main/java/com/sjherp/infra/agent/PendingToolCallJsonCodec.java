package com.sjherp.infra.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import com.sjherp.agent.loop.PendingToolCall;

/**
 * {@link PendingToolCall}（高风险拦截的中断现场）的 JSON 编解码器（M1-T03）。
 *
 * <p>执行循环拦截高风险工具后，app 层用本编解码器把现场序列化存入
 * agent_session.pending_tool_call 列（V3 迁移）；用户点击确认 / 取消后
 * 反序列化恢复并交给 {@code AgentLoop.resume}。Jackson 依赖只出现在 infra，
 * agent 模块零依赖（PendingToolCall 及其嵌套 ToolCall 均为 record，
 * Jackson 原生支持，无需注解）。
 */
public final class PendingToolCallJsonCodec {

    private final ObjectMapper mapper = JsonMapper.builder()
            // 字段演进时旧数据多出的字段不报错（向前兼容）
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    /** 序列化为 JSON（存入 agent_session.pending_tool_call 列） */
    public String toJson(PendingToolCall pending) {
        try {
            return mapper.writeValueAsString(pending);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PendingToolCall 序列化失败", e);
        }
    }

    /** 从 JSON 反序列化（恢复确认流程时使用） */
    public PendingToolCall fromJson(String json) {
        try {
            return mapper.readValue(json, PendingToolCall.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PendingToolCall 反序列化失败: " + json, e);
        }
    }
}
