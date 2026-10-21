package com.sjherp.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;

class RedactingLlmClientTest {

    @Test
    void 发给下游的用户消息已脱敏() {
        AtomicReference<List<LlmMessage>> captured = new AtomicReference<>();
        LlmClient inner = (messages, options) -> {
            captured.set(messages);
            return new LlmResponse("ok");
        };
        RedactingLlmClient client = new RedactingLlmClient(inner);
        client.chat(List.of(LlmMessage.user("联系电话13812345678")), LlmRequestOptions.defaults());
        assertThat(captured.get().get(0).content()).isEqualTo("联系电话[已脱敏-手机]");
    }
}
