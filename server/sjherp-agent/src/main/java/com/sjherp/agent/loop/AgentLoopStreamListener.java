package com.sjherp.agent.loop;

/**
 * 执行循环的终轮流式回调（M7-T03）。
 *
 * <p>只在<b>已知终轮</b>（无工具的单轮、{@link FinalJsonMode#JSON_SEPARATE_FINAL_CALL}
 * 的单独 JSON 调用、迭代用尽后的强制收尾）转发模型 content 增量。
 * 工具轮绝不转发——避免把 tool_calls 参数或半成品 JSON 泄漏给用户，
 * 也避免高风险拦截前的中间输出破坏 HITL 确认语义。
 */
public interface AgentLoopStreamListener {

    /** 终轮模型 content 增量（可能是协议 JSON 碎片，由上层抽出 text） */
    void onFinalContentDelta(String delta);
}
