package com.sjherp.app.chat;

import java.util.Map;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.Option;
import com.sjherp.agent.session.AgentSession;

/**
 * 聊天 Agent 抽象：把一条用户输入（文本 / 点选项 / 提交表单）转成协议回复。
 *
 * <p>实现：{@link LlmAgent}（LLM 驱动）与 {@link PlaceholderAgent}（规则占位），
 * 由 {@code ChatAgentConfig} 按 {@code sjherp.agent.mode} 装配切换。
 *
 * <p>约定：传入的 {@code session} 只含<b>历史</b>消息（当前这条用户输入
 * 尚未 append，由 ChatService 在拿到回复后统一落库），实现方需要上下文时
 * 自行从 session 读取历史。
 */
public interface Agent {

    /** 处理用户自由文本 */
    AgentReply replyToText(AgentSession session, String text);

    /** 处理用户点击的选项（已由 ChatService 凭最近一条回复按 id 还原，防伪造） */
    AgentReply replyToOption(AgentSession session, Option option);

    /** 处理用户提交的表单（values 一律字符串，金额/数量由后端 BigDecimal 解析） */
    AgentReply replyToForm(AgentSession session, String formId, Map<String, String> values);
}
