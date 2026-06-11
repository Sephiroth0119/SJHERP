package com.sjherp.agent.reply;

import java.util.List;
import java.util.Objects;

/**
 * Agent 结构化回复——选项返回协议 v0.1（前后端核心契约，docs/选项返回协议.md）。
 *
 * <p>Agent 回复永远是结构化消息：文本 + 可选项数组 + 可选表单。
 * 前端将 options 渲染为可点击卡片、将 form 渲染为表单，用户点击/提交后
 * 以结构化输入（optionId / formId+values）回传，避免让用户打字描述一切。
 *
 * <p>Human-in-the-loop：涉及资金、过账、期间关账等高风险操作，回复必须置
 * requiresConfirmation=true（此时 options 必含"确认执行"与"取消"两项，
 * 确认项 risk=HIGH），由人点击确认后才执行，不允许直接执行。
 *
 * <p>本协议变更需文档化在 docs/ 中并保持版本化。
 *
 * @param version              协议版本，每条回复必带，当前 {@link #PROTOCOL_VERSION}
 * @param text                 回复正文（必填，markdown 文本）
 * @param options              可点击选项，可为空列表（表示无需用户决策）
 * @param form                 可选表单：需要用户补充多个字段时使用；无表单时为 null
 * @param requiresConfirmation Human-in-the-loop 标记（回复级），默认 false；
 *                             true 时 Agent 只准备动作，由人点击确认后才执行
 */
public record AgentReply(String version, String text, List<Option> options,
                         Form form, boolean requiresConfirmation) {

    /** 当前协议版本（与 docs/选项返回协议.md、web/src/types/agent.ts 保持一致） */
    public static final String PROTOCOL_VERSION = "0.1";

    public AgentReply {
        version = version == null ? PROTOCOL_VERSION : version;
        Objects.requireNonNull(text, "text 不能为空");
        options = options == null ? List.of() : List.copyOf(options);
        if (requiresConfirmation && options.isEmpty()) {
            throw new IllegalArgumentException(
                    "requiresConfirmation=true 时 options 必须非空（须含确认与取消两项）");
        }
    }

    /** 纯文本回复（无选项、无表单） */
    public static AgentReply text(String text) {
        return new AgentReply(PROTOCOL_VERSION, text, List.of(), null, false);
    }

    /** 文本 + 选项卡片 */
    public static AgentReply withOptions(String text, List<Option> options) {
        return new AgentReply(PROTOCOL_VERSION, text, options, null, false);
    }

    /** 文本 + 表单 */
    public static AgentReply withForm(String text, Form form) {
        return new AgentReply(PROTOCOL_VERSION, text, List.of(), form, false);
    }

    /** 高风险确认回复（Human-in-the-loop）：options 须含确认（risk=HIGH）与取消两项 */
    public static AgentReply confirmation(String text, List<Option> options) {
        return new AgentReply(PROTOCOL_VERSION, text, options, null, true);
    }
}
