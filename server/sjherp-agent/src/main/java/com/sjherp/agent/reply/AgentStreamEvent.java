package com.sjherp.agent.reply;

import java.util.Locale;
import java.util.Objects;

/**
 * 选项返回协议 v0.2 的流式分片事件——前后端 SSE 契约。
 *
 * <p>完整回复仍是 {@link AgentReply}（字段与 v0.1 兼容）。流式是增量分片：
 * {@code delta} 只推正文，{@code complete} 携带可还原的完整 AgentReply
 * （含 options / form / requiresConfirmation）。非流式客户端继续消费完整 JSON，
 * 不必理解本事件。
 *
 * @param version 流式分片协议版本，当前 {@link #PROTOCOL_VERSION}
 * @param type    事件类型
 * @param status  {@link Type#STATUS} 时的状态码，其余为 null
 * @param text    {@link Type#DELTA} 时的正文增量，其余为 null
 * @param reply   {@link Type#COMPLETE} 时的完整回复，其余为 null
 * @param error   {@link Type#ERROR} 时的可读错误原文，其余为 null
 */
public record AgentStreamEvent(String version, Type type, String status, String text,
                               AgentReply reply, String error) {

    /** 流式分片协议版本（与 docs/选项返回协议.md、web/src/types/agent.ts 保持一致） */
    public static final String PROTOCOL_VERSION = "0.2";

    public AgentStreamEvent {
        version = version == null || version.isBlank() ? PROTOCOL_VERSION : version;
        Objects.requireNonNull(type, "type 不能为空");
        switch (type) {
            case STATUS -> Objects.requireNonNull(status, "status 事件必须带 status");
            case DELTA -> Objects.requireNonNull(text, "delta 事件必须带 text");
            case COMPLETE -> Objects.requireNonNull(reply, "complete 事件必须带 reply");
            case ERROR -> Objects.requireNonNull(error, "error 事件必须带 error");
        }
    }

    public static AgentStreamEvent status(String status) {
        return new AgentStreamEvent(PROTOCOL_VERSION, Type.STATUS, status, null, null, null);
    }

    public static AgentStreamEvent delta(String text) {
        return new AgentStreamEvent(PROTOCOL_VERSION, Type.DELTA, null, text, null, null);
    }

    public static AgentStreamEvent complete(AgentReply reply) {
        return new AgentStreamEvent(PROTOCOL_VERSION, Type.COMPLETE, null, null, reply, null);
    }

    public static AgentStreamEvent error(String error) {
        return new AgentStreamEvent(PROTOCOL_VERSION, Type.ERROR, null, null, null, error);
    }

    /**
     * 流式事件类型。JSON 映射规则与协议其它枚举一致：JSON 值 = 枚举名小写。
     */
    public enum Type {
        /** 处理阶段提示（如 thinking），不改变正文 */
        STATUS,
        /** 正文增量，按到达顺序拼接即等于最终 {@link AgentReply#text()} */
        DELTA,
        /** 流结束：完整 AgentReply，前端此时才渲染 options/form */
        COMPLETE,
        /** 失败：error 为可展示的后端原文 */
        ERROR;

        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type fromJson(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("stream event type 不能为空");
            }
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
    }
}
