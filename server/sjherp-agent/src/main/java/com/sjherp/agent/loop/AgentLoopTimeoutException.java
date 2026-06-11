package com.sjherp.agent.loop;

/**
 * 执行循环超出整体时间预算（{@code AgentLoopRequest.timeout}）时抛出。
 *
 * <p>属于防护性中断：上层（app 的 LlmAgent）捕获后给用户致歉兜底文案，
 * 不把异常透给前端。
 */
public class AgentLoopTimeoutException extends RuntimeException {

    public AgentLoopTimeoutException(String message) {
        super(message);
    }
}
