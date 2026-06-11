package com.sjherp.infra.llm;

/**
 * LLM 厂商调用异常（非 200、超时、网络异常、响应结构异常等）。
 *
 * <p>异常消息须带足够上下文（厂商、端点、状态码、响应摘要），
 * 由上层（如 LlmAgent）决定兜底策略，本层只如实抛出。
 */
public class LlmClientException extends RuntimeException {

    public LlmClientException(String message) {
        super(message);
    }

    public LlmClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
