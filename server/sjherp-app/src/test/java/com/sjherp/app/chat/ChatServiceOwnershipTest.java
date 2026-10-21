package com.sjherp.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.agent.reply.AgentReply;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.AgentSessionSummary;
import com.sjherp.agent.session.InMemoryAgentSessionRepository;
import com.sjherp.agent.session.SessionStatus;
import com.sjherp.infra.agent.AgentReplyJsonCodec;

/**
 * 会话归属校验测试（P1 越权修复验收）：
 * 用户 A 建会话、用户 B 持 sessionId 读历史 / 续对话一律按「会话不存在」拒绝
 * （SessionNotFoundException → API 404，不用 403 避免泄露会话存在性）；
 * 本人访问不受影响。ADMIN 不豁免（本次从严，管理侧查询留给以后专门接口）。
 */
class ChatServiceOwnershipTest {

    private static final String USER_A = "1";
    private static final String USER_B = "2";

    private InMemoryAgentSessionRepository repository;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAgentSessionRepository();
        // PlaceholderAgent：无 LLM 的规则实现，足够驱动收发消息链路
        chatService = new ChatService(repository, new AgentReplyJsonCodec(), new PlaceholderAgent());
    }

    @Test
    void 本人可读取会话与发送消息() {
        AgentSession session = chatService.createSession(USER_A);

        AgentSession loaded = chatService.getSession(session.getSessionId(), USER_A);
        assertThat(loaded.getSessionId()).isEqualTo(session.getSessionId());

        AgentReply reply = chatService.handleMessage(session.getSessionId(), USER_A,
                new SendMessageRequest("你好", null, null, null));
        assertThat(reply.text()).isNotBlank();
        // 用户消息 + Agent 回复均已落库
        assertThat(repository.findById(session.getSessionId()).orElseThrow().getMessages()).hasSize(2);
    }

    @Test
    void 他人读取会话按不存在处理() {
        AgentSession session = chatService.createSession(USER_A);

        // 错误信息与"真不存在"完全一致，不泄露会话存在性
        assertThatThrownBy(() -> chatService.getSession(session.getSessionId(), USER_B))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("会话不存在: " + session.getSessionId());
    }

    @Test
    void 他人发送消息按不存在处理_且不落库() {
        AgentSession session = chatService.createSession(USER_A);

        assertThatThrownBy(() -> chatService.handleMessage(session.getSessionId(), USER_B,
                new SendMessageRequest("窃取上下文", null, null, null)))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("会话不存在: " + session.getSessionId());

        // 越权请求不得污染他人会话历史
        assertThat(repository.findById(session.getSessionId()).orElseThrow().getMessages()).isEmpty();
    }

    @Test
    void 会话确实不存在时同样404() {
        assertThatThrownBy(() -> chatService.getSession("no-such-session", USER_A))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("会话不存在: no-such-session");
    }

    @Test
    void 会话列表只返回本人且按更新时间降序() {
        AgentSession older = chatService.createSession(USER_A);
        AgentSession newer = chatService.createSession(USER_A);
        chatService.createSession(USER_B);
        chatService.handleMessage(newer.getSessionId(), USER_A,
                new SendMessageRequest("给华东金属下采购单", null, null, null));

        var list = chatService.listSessions(USER_A);
        assertThat(list).extracting(AgentSessionSummary::sessionId)
                .containsExactly(newer.getSessionId(), older.getSessionId());
        assertThat(list).extracting(AgentSessionSummary::userId).containsOnly(USER_A);
        assertThat(list.get(0).title()).isEqualTo("给华东金属下采购单");
        assertThat(chatService.listSessions(USER_B)).hasSize(1)
                .extracting(AgentSessionSummary::sessionId)
                .doesNotContain(older.getSessionId(), newer.getSessionId());
    }

    @Test
    void 他人重命名与归档按不存在处理() {
        AgentSession session = chatService.createSession(USER_A);

        assertThatThrownBy(() -> chatService.renameSession(session.getSessionId(), USER_B, "窃取标题"))
                .isInstanceOf(SessionNotFoundException.class);
        assertThatThrownBy(() -> chatService.archiveSession(session.getSessionId(), USER_B))
                .isInstanceOf(SessionNotFoundException.class);

        AgentSession loaded = repository.findById(session.getSessionId()).orElseThrow();
        assertThat(loaded.getTitle()).isNull();
        assertThat(loaded.getStatus()).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void 本人可重命名并归档_归档后拒绝发消息且无物理删除() {
        AgentSession session = chatService.createSession(USER_A);
        chatService.renameSession(session.getSessionId(), USER_A, "华东金属采购");
        AgentSession renamed = chatService.archiveSession(session.getSessionId(), USER_A);

        assertThat(renamed.getTitle()).isEqualTo("华东金属采购");
        assertThat(renamed.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(chatService.archiveSession(session.getSessionId(), USER_A).getStatus())
                .isEqualTo(SessionStatus.CLOSED);

        assertThatThrownBy(() -> chatService.handleMessage(session.getSessionId(), USER_A,
                new SendMessageRequest("归档后还想发", null, null, null)))
                .isInstanceOf(SessionClosedException.class);

        assertThat(repository.findById(session.getSessionId())).isPresent();
        assertThat(chatService.getSession(session.getSessionId(), USER_A).getStatus())
                .isEqualTo(SessionStatus.CLOSED);
    }

    @Test
    void 空标题拒绝重命名() {
        AgentSession session = chatService.createSession(USER_A);
        assertThatThrownBy(() -> chatService.renameSession(session.getSessionId(), USER_A, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话标题不能为空");
    }
}
