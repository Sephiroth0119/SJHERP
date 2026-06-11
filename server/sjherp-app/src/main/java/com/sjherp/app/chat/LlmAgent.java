package com.sjherp.app.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.Option;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

/**
 * LLM 驱动的聊天 Agent：把会话历史转成 LLM 上下文，要求模型直接输出
 * 符合选项返回协议 v0.1 的 JSON，并用 {@link AgentReplyJsonCodec} 反序列化。
 *
 * <p>容错策略（不让用户看到异常）：
 * <ul>
 *   <li>模型输出不符合协议 → 把原始文本包成纯文本 AgentReply（version 由构造器补 0.1）；</li>
 *   <li>LLM 调用失败（超时/网络/非 200）→ 记录 ERROR 日志并返回致歉文本。</li>
 * </ul>
 *
 * <p>注意：当前尚未接入任何业务工具，系统提示词中明确要求模型不得编造
 * 库存/订单等业务数据（CLAUDE.md「流程缺口」理念的雏形）。
 */
public class LlmAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(LlmAgent.class);

    /** 仅用于把表单 values 序列化进用户消息（与协议编解码无关） */
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    /** 聊天链路要求模型输出协议 JSON：按次启用 response_format=json_object（行为与原构造参数开关一致） */
    private static final LlmRequestOptions JSON_REPLY_OPTIONS =
            LlmRequestOptions.builder().jsonResponseFormat(true).build();

    /**
     * 系统提示词：协议字段结构、optionId 回传机制、Human-in-the-loop 规则、
     * decimal 字符串约定均取自 docs/选项返回协议.md（v0.1 定稿），
     * 能力边界部分对应 CLAUDE.md「流程缺口通道」。
     */
    private static final String SYSTEM_PROMPT = """
            你是 SJHERP 的业务助手。SJHERP 是面向小型企业的进销存 + 生产 + 财务一体化 ERP，\
            用户通过和你聊天完成业务操作（采购、销售、库存、生产、财务）。

            ## 输出格式（最高优先级硬性要求）
            你的每次回复必须是且只能是一个 JSON 对象，符合「选项返回协议 v0.1」。\
            不要输出 JSON 以外的任何内容（不要用 markdown 代码块包裹，不要附加解释文字）。

            协议字段：
            - version：string，必填，固定为 "0.1"。
            - text：string，必填，markdown 文本，是展示给用户的消息正文（中文）。
            - options：数组，可选。每个元素渲染为可点击卡片，字段：
              - id：string，必填，一条回复内唯一（如 "opt-confirm"）。用户点击后系统只回传这个 id，\
            你随后会收到一条形如「[用户点击选项] <该选项的label>」的用户消息——所以 label 必须语义自含。
              - label：string，必填，卡片标题（短，中文）。
              - description：string，可选，副文案（如报价、交期、风险提示）。
              - risk：可选，"normal"（默认）或 "high"。"high" 表示该选项触发高风险动作，\
            前端用醒目警示样式渲染，且只允许出现在 requiresConfirmation=true 的回复中。
              - action：可选，形如 {"type": "...", "params": {...}}，声明点中后要执行的动作。\
            当前系统没有任何已注册的可执行动作，请省略 action（选项仅作语义化回答，点击后继续对话）。
            - form：对象，可选。需要用户补充多个结构化字段时返回，结构：
              {"id": "...", "title": "...", "submitLabel": "...", "fields": [...], "submitAction": {"type": "...", "params": {}}}
              fields 每项：{"name": 英文标识符, "label": 中文标签, "type": "...", "required": true/false, \
            "placeholder": "...", "defaultValue": "...", "options": [{"value": "...", "label": "..."}]}
              type 只能是小写的 text / decimal / integer / date / select；\
            select 必须带 options；金额/数量/单价一律用 decimal；defaultValue 一律字符串。
            - requiresConfirmation：boolean，可选，默认 false，含义见下。

            ## 高风险操作必须人工确认（Human-in-the-loop，硬性要求）
            凡涉及创建/提交业务单据（下采购单、下销售订单）、过账、付款、收款、期间关账、冲销等\
            会产生或变更单据与资金的操作：
            1. 必须 requiresConfirmation=true；
            2. options 必须同时包含明确的「确认执行」类选项（risk="high"）和「取消」类选项（risk 省略）；
            3. 你只准备动作、绝不当场执行，等用户点击确认选项后再继续。
            约束：risk="high" 的选项只允许出现在 requiresConfirmation=true 的回复中；\
            requiresConfirmation=true 时 options 必须非空且含确认与取消两项。

            ## 数值精度（硬性要求）
            金额、数量、单价等精度敏感值在 JSON 中一律用字符串表示（如 "qty": "500"、"defaultValue": "18.50"），\
            绝不用 JSON 数字承载，后端以 BigDecimal 解析。

            ## 能力边界（硬性要求，绝不违反）
            目前系统尚未给你接入任何业务工具：你无法查询库存、订单、供应商、财务等真实数据，\
            也无法真正创建任何单据。因此：
            1. 绝不编造库存数量、订单号、供应商报价、交期等任何具体业务数据；
            2. 用户问及具体业务数据或要求执行业务操作时，在 text 中如实说明该能力尚未接入；
            3. 同时仍要有用：用 options 给出后续引导（例如「记录这个需求，待功能上线后处理」「换个我能帮上的事」），\
            或用 form 帮用户把需求结构化地记录下来——这是系统"流程缺口"记录机制的雏形；
            4. 即便无法真正执行，下单等高风险意图仍必须先走 requiresConfirmation 确认流程；\
            用户确认后，如实说明已记录意图但未真正创建单据。

            ## 语言
            text、label、description、title 等用户可见文案一律使用中文；\
            id、name、action.type 等标识符用英文。
            """;

    private final LlmClient llmClient;
    private final AgentReplyJsonCodec codec;

    public LlmAgent(LlmClient llmClient, AgentReplyJsonCodec codec) {
        this.llmClient = llmClient;
        this.codec = codec;
    }

    @Override
    public AgentReply replyToText(AgentSession session, String text) {
        return complete(session, text);
    }

    @Override
    public AgentReply replyToOption(AgentSession session, Option option) {
        // 协议约定前端只回传 optionId，ChatService 已按 id 还原；这里把 label 回灌给模型
        return complete(session, "[用户点击选项] " + option.label());
    }

    @Override
    public AgentReply replyToForm(AgentSession session, String formId, Map<String, String> values) {
        return complete(session, "[用户提交表单 " + formId + "] 表单值：" + toJson(values));
    }

    /** 系统提示 + 会话历史 + 当前用户输入 → LLM → 协议回复 */
    private AgentReply complete(AgentSession session, String currentUserText) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(SYSTEM_PROMPT));
        for (AgentMessage message : session.getMessages()) {
            switch (message.role()) {
                case USER -> messages.add(LlmMessage.user(message.content()));
                // assistant 消息落库时即为 AgentReply 协议 JSON，原样作为 assistant 上下文
                case ASSISTANT -> messages.add(LlmMessage.assistant(message.content()));
                default -> { /* SYSTEM/TOOL 历史暂不进入上下文 */ }
            }
        }
        messages.add(LlmMessage.user(currentUserText));

        String raw;
        try {
            LlmResponse response = llmClient.chat(messages, JSON_REPLY_OPTIONS);
            raw = response.content();
        } catch (RuntimeException e) {
            // LLM 调用失败（超时/网络/非 200）：不把异常抛给用户，给致歉兜底
            log.error("LLM 调用失败，返回兜底回复（sessionId={}）", session.getSessionId(), e);
            return AgentReply.text("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
        return parseReply(raw);
    }

    /** 模型输出 → AgentReply；不符合协议时降级为纯文本回复（version 由构造器补 0.1） */
    private AgentReply parseReply(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("模型返回空内容，降级为提示文本");
            return AgentReply.text("（模型未返回内容，请重试）");
        }
        try {
            // 防御：即便启用 json_object，仍兼容偶发的代码块包裹
            return codec.fromJson(stripCodeFence(raw));
        } catch (RuntimeException e) {
            log.warn("模型输出不符合选项返回协议，降级为纯文本回复：{}", e.getMessage());
            return AgentReply.text(raw);
        }
    }

    /** 去掉 ```json ... ``` 代码块包裹（防御性处理） */
    private static String stripCodeFence(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int fenceEnd = trimmed.lastIndexOf("```");
            if (firstLineEnd > 0 && fenceEnd > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, fenceEnd).strip();
            }
        }
        return trimmed;
    }

    private static String toJson(Map<String, String> values) {
        try {
            return PLAIN_MAPPER.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException e) {
            return String.valueOf(values);
        }
    }
}
