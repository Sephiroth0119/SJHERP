package com.sjherp.app.chat;

import com.sjherp.agent.reply.AgentReply;

/**
 * Agent 生成过程的增量回调（M7-T03）。
 *
 * <p>{@link #onTextDelta(String)} 只推已经抽出的用户可见正文；
 * options / form / requiresConfirmation 只出现在 {@link #onComplete(AgentReply)}。
 * ChatService 在落库之后才把 complete 事件推给 SSE，保证回放与流式最终态一致。
 */
public interface AgentReplySink {

    /** 处理阶段提示（thinking 等），可忽略 */
    void onStatus(String status);

    /** 正文增量 */
    void onTextDelta(String text);

    /** 完整回复已产生（尚未由 ChatService 落库） */
    void onComplete(AgentReply reply);
}
