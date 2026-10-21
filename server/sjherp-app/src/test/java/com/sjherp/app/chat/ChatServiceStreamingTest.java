package com.sjherp.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.reply.AgentStreamEvent;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.InMemoryAgentSessionRepository;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

class ChatServiceStreamingTest {

    private static final String USER_A = "1";
    private static final String USER_B = "2";

    private InMemoryAgentSessionRepository repository;
    private ChatService chatService;
    private AgentReplyJsonCodec codec;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAgentSessionRepository();
        codec = new AgentReplyJsonCodec();
        chatService = new ChatService(repository, codec, new PlaceholderAgent());
    }

    @Test
    void 流式分片可还原完整回复且落库后再发complete() {
        AgentSession session = chatService.createSession(USER_A);
        List<AgentStreamEvent> events = new ArrayList<>();

        chatService.handleMessageStreaming(session.getSessionId(), USER_A,
                new SendMessageRequest("帮我看看库存", null, null, null), events::add);

        assertThat(events.get(0).type()).isEqualTo(AgentStreamEvent.Type.STATUS);
        AgentStreamEvent complete = events.get(events.size() - 1);
        assertThat(complete.type()).isEqualTo(AgentStreamEvent.Type.COMPLETE);
        AgentReply reply = complete.reply();
        String streamed = events.stream()
                .filter(event -> event.type() == AgentStreamEvent.Type.DELTA)
                .map(AgentStreamEvent::text)
                .reduce("", String::concat);
        assertThat(streamed).isEqualTo(reply.text());
        assertThat(reply.options()).isNotEmpty();
        events.stream()
                .filter(event -> event.type() == AgentStreamEvent.Type.DELTA)
                .forEach(event -> assertThat(event.reply()).isNull());

        AgentSession persisted = repository.findById(session.getSessionId()).orElseThrow();
        assertThat(persisted.getMessages()).hasSize(2);
        AgentReply stored = codec.fromJson(persisted.getMessages().get(1).content());
        assertThat(stored.text()).isEqualTo(reply.text());
        assertThat(stored.options()).hasSize(reply.options().size());
    }

    @Test
    void 非流式入口仍返回完整回复() {
        AgentSession session = chatService.createSession(USER_A);
        AgentReply reply = chatService.handleMessage(session.getSessionId(), USER_A,
                new SendMessageRequest("你好", null, null, null));
        assertThat(reply.text()).isNotBlank();
        assertThat(reply.version()).isEqualTo(AgentReply.PROTOCOL_VERSION);
    }

    @Test
    void 他人流式发送按不存在处理() {
        AgentSession session = chatService.createSession(USER_A);
        assertThatThrownBy(() -> chatService.handleMessageStreaming(session.getSessionId(), USER_B,
                new SendMessageRequest("窃取", null, null, null), event -> {
                }))
                .isInstanceOf(SessionNotFoundException.class);
        assertThat(repository.findById(session.getSessionId()).orElseThrow().getMessages()).isEmpty();
    }
}