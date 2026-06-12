package com.sjherp.agent.history;

import com.sjherp.agent.session.MessageRole;

/**
 * 参与历史窗口裁剪的一条消息（M1-T05）。
 *
 * <p>裁剪纯函数的输入 / 输出单元：只携带裁剪所需的最小信息，
 * 与会话持久化模型（AgentMessage）解耦。
 *
 * @param seq     会话内持久化序号（对应 agent_message.seq，从 1 开始）；
 *                摘要覆盖范围（summarized_until_seq）以此为准
 * @param role    消息角色（裁剪只关心 USER / ASSISTANT）
 * @param content 消息内容
 */
public record HistoryMessage(int seq, MessageRole role, String content) {
}
