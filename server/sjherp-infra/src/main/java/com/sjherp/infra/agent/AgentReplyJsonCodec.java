package com.sjherp.infra.agent;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.AgentStreamEvent;
import com.sjherp.agent.reply.DocumentRef;
import com.sjherp.agent.reply.Form;
import com.sjherp.agent.reply.Option;

/**
 * AgentReply / AgentStreamEvent（选项返回协议 v0.2）的 JSON 编解码器。
 *
 * <p>Jackson 依赖刻意只出现在 infra 层——sjherp-agent 模块保持零依赖纯 Java。
 * 协议映射规则（docs/选项返回协议.md）：枚举 JSON 值 = 枚举名小写
 * （RiskLevel.NORMAL/HIGH ↔ "normal"/"high"，FieldType 同理），
 * 序列化输出小写、反序列化大小写不敏感；可选字段为 null 时不输出。
 */
public final class AgentReplyJsonCodec {

    private final ObjectMapper mapper;

    public AgentReplyJsonCodec() {
        SimpleModule protocol = new SimpleModule("sjherp-agent-protocol-v0.2");
        // RiskLevel：JSON 小写 <-> 枚举（大小写不敏感）
        protocol.addSerializer(Option.RiskLevel.class, new JsonSerializer<>() {
            @Override
            public void serialize(Option.RiskLevel value, JsonGenerator gen, SerializerProvider sp) throws IOException {
                gen.writeString(value.json());
            }
        });
        protocol.addDeserializer(Option.RiskLevel.class, new JsonDeserializer<>() {
            @Override
            public Option.RiskLevel deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                return Option.RiskLevel.fromJson(p.getValueAsString());
            }
        });
        // FieldType：JSON 小写 <-> 枚举（大小写不敏感）
        protocol.addSerializer(Form.FieldType.class, new JsonSerializer<>() {
            @Override
            public void serialize(Form.FieldType value, JsonGenerator gen, SerializerProvider sp) throws IOException {
                gen.writeString(value.json());
            }
        });
        protocol.addDeserializer(Form.FieldType.class, new JsonDeserializer<>() {
            @Override
            public Form.FieldType deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                return Form.FieldType.fromJson(p.getValueAsString());
            }
        });
        protocol.addSerializer(AgentStreamEvent.Type.class, new JsonSerializer<>() {
            @Override
            public void serialize(AgentStreamEvent.Type value, JsonGenerator gen, SerializerProvider sp)
                    throws IOException {
                gen.writeString(value.json());
            }
        });
        protocol.addDeserializer(AgentStreamEvent.Type.class, new JsonDeserializer<>() {
            @Override
            public AgentStreamEvent.Type deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                return AgentStreamEvent.Type.fromJson(p.getValueAsString());
            }
        });

        this.mapper = JsonMapper.builder()
                .addModule(protocol)
                // 可选字段缺省时不输出 null，与协议文档示例保持一致
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                // 协议演进时旧服务读到新增字段不报错（向前兼容）
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .addMixIn(AgentReply.class, AgentReplyMixin.class)
                .build();
    }

    /**
     * 仅空 documents 不输出，保持与旧 v0.1 回复字节兼容（无该字段的客户端忽略即可）。
     * 注解只打在 documents，避免把空 options 或 false 的 requiresConfirmation 一并省略。
     */
    private abstract static class AgentReplyMixin {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        abstract java.util.List<DocumentRef> documents();
    }

    /** 序列化为协议 JSON（用于持久化与 API 响应） */
    public String toJson(AgentReply reply) {
        try {
            return mapper.writeValueAsString(reply);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AgentReply 序列化失败", e);
        }
    }

    /** 从协议 JSON 反序列化（用于会话恢复后按 optionId 还原选项语义与 action） */
    public AgentReply fromJson(String json) {
        try {
            return mapper.readValue(json, AgentReply.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AgentReply 反序列化失败: " + json, e);
        }
    }

    /** 解析为 JsonNode（用于会话回放时把存储的 JSON 原样嵌入响应，保证字节级协议一致） */
    public JsonNode toTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AgentReply JSON 解析失败: " + json, e);
        }
    }

    /** 序列化流式分片事件（SSE data 行） */
    public String toStreamJson(AgentStreamEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AgentStreamEvent 序列化失败", e);
        }
    }

    /** 反序列化流式分片事件（测试与重建完整回复） */
    public AgentStreamEvent fromStreamJson(String json) {
        try {
            return mapper.readValue(json, AgentStreamEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AgentStreamEvent 反序列化失败: " + json, e);
        }
    }
}
