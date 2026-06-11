package com.sjherp.infra.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sjherp.agent.tool.ToolArgumentsCodec;

/**
 * {@link ToolArgumentsCodec} 的 Jackson 实现（M1-T02/T03）。
 *
 * <p>Jackson 依赖刻意只出现在 infra 层——sjherp-agent 模块零依赖、只定义接口。
 * 由 app 层装配注入 AgentLoop。
 *
 * <p>序列化约定：用 LinkedHashMap 保持插入顺序输出（AgentLoop 依赖
 * {@code {"success":true...}} 前缀判定工具是否成功，success 必须是首字段）。
 */
public final class JacksonToolArgumentsCodec implements ToolArgumentsCodec {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Map<String, Object> parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            // 无参数工具：模型可能给空串，视为无参数
            return Map.of();
        }
        try {
            return mapper.readValue(argumentsJson, MAP_TYPE);
        } catch (JsonProcessingException e) {
            // 接口契约：JSON 不合法抛 IllegalArgumentException（执行循环把错误回灌给模型）
            throw new IllegalArgumentException("工具调用参数不是合法 JSON 对象: " + e.getOriginalMessage(), e);
        }
    }

    @Override
    public String serialize(Map<String, Object> data) {
        try {
            return mapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具结果序列化失败", e);
        }
    }
}
