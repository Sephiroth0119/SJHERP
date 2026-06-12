package com.sjherp.app.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.Option;
import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.AgentSessionRepository;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

/**
 * 会话应用服务：创建会话、收发消息、持久化（用户消息与 Agent 回复都落库）。
 *
 * <p>选项回传按协议处理：前端只回传 optionId，本服务凭会话中最近一条
 * Agent 回复的 options 按 id 还原选项语义与 action（防止前端伪造动作参数）。
 */
@Service
public class ChatService {

    /** 会话标题取首条用户消息摘要的最大长度 */
    private static final int TITLE_MAX_LENGTH = 50;

    private final AgentSessionRepository repository;
    private final AgentReplyJsonCodec codec;
    private final Agent agent;

    public ChatService(AgentSessionRepository repository, AgentReplyJsonCodec codec, Agent agent) {
        this.repository = repository;
        this.codec = codec;
        this.agent = agent;
    }

    /** 创建新会话并立即落库（userId 为登录用户标识，Controller 层从登录态解析，M2-T05） */
    public AgentSession createSession(String userId) {
        AgentSession session = new AgentSession(UUID.randomUUID().toString(), userId);
        repository.save(session);
        return session;
    }

    /** 按 ID 加载会话（不存在抛 404） */
    public AgentSession getSession(String sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));
    }

    /**
     * 处理一条用户消息（text / optionId / formId 三选一），
     * 持久化用户消息与 Agent 回复后返回回复。
     */
    public AgentReply handleMessage(String sessionId, SendMessageRequest request) {
        AgentSession session = getSession(sessionId);

        // 约定：先以「只含历史」的 session 调 Agent（Agent 接口约定），拿到回复后再统一落库
        AgentReply reply;
        if (request.text() != null && !request.text().isBlank()) {
            // 自由文本：用户消息原文落库
            reply = agent.replyToText(session, request.text());
            session.append(AgentMessage.user(request.text()));
            if (session.getTitle() == null) {
                session.setTitle(truncate(request.text()));
            }
        } else if (request.optionId() != null) {
            // 点击选项：凭最近一条 Agent 回复按 id 还原；用户气泡显示选项 label（协议约定）
            Option option = resolveOption(session, request.optionId());
            reply = agent.replyToOption(session, option);
            session.append(AgentMessage.user(option.label()));
        } else if (request.formId() != null) {
            // 提交表单：values 一律字符串（金额/数量后端 BigDecimal 解析，禁止 float/double）
            Map<String, String> values = request.values() == null ? Map.of() : request.values();
            reply = agent.replyToForm(session, request.formId(), values);
            session.append(AgentMessage.user("提交表单 " + request.formId() + "：" + values));
        } else {
            throw new IllegalArgumentException("请求体必须提供 text / optionId / formId 三者之一");
        }

        // Agent 回复以协议 JSON 形式落库（回放时原样返回）
        session.append(AgentMessage.assistant(codec.toJson(reply)));
        repository.save(session);
        return reply;
    }

    /** 从会话最近一条 Agent 回复中按 id 还原选项（协议「回传机制」第 1 条） */
    private Option resolveOption(AgentSession session, String optionId) {
        List<AgentMessage> messages = session.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            if (message.role() == MessageRole.ASSISTANT) {
                AgentReply lastReply = codec.fromJson(message.content());
                return lastReply.options().stream()
                        .filter(option -> option.id().equals(optionId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "选项不存在或已过期: " + optionId));
            }
        }
        throw new IllegalArgumentException("当前会话没有可点击的选项");
    }

    private static String truncate(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH);
    }
}
