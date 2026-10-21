package com.sjherp.app.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.LlmStreamHandler;

/**
 * 在真正调用厂商客户端前对消息正文做 {@link LlmRedactor} 脱敏。
 */
public final class RedactingLlmClient implements LlmClient {

    private final LlmClient delegate;

    public RedactingLlmClient(LlmClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options) {
        return delegate.chat(redactAll(messages), options);
    }

    @Override
    public void chatStream(List<LlmMessage> messages, LlmRequestOptions options, LlmStreamHandler handler) {
        delegate.chatStream(redactAll(messages), options, handler);
    }

    private static List<LlmMessage> redactAll(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<LlmMessage> redacted = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            if (message.content() == null) {
                redacted.add(message);
            } else {
                redacted.add(new LlmMessage(
                        message.role(),
                        LlmRedactor.redact(message.content()),
                        message.toolCalls(),
                        message.toolCallId()));
            }
        }
        return List.copyOf(redacted);
    }
}
