package com.sjherp.app.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.InMemoryAgentSessionRepository;
import com.sjherp.app.chat.ChatService;
import com.sjherp.app.chat.PlaceholderAgent;
import com.sjherp.app.chat.SessionNotFoundException;
import com.sjherp.app.invocation.AgentInvocationDtos.InvocationPageResponse;
import com.sjherp.app.security.AuthenticatedUser;
import com.sjherp.domain.common.PageResult;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository.TokenSummary;

/**
 * 调用观测查询 API 归属校验测试（P2 越权修复验收）：
 * GET /api/agent/invocations 先按 sessionId 校验会话归属，
 * 非本人 / 会话不存在统一 SessionNotFoundException → 404（不泄露存在性），
 * 且不触达调用记录仓储；本人查询正常返回分页数据。
 */
class AgentInvocationControllerOwnershipTest {

    private AgentInvocationRepository invocationRepository;
    private ChatService chatService;
    private AgentInvocationController controller;

    @BeforeEach
    void setUp() {
        invocationRepository = mock(AgentInvocationRepository.class);
        chatService = new ChatService(new InMemoryAgentSessionRepository(),
                new AgentReplyJsonCodec(), new PlaceholderAgent());
        controller = new AgentInvocationController(invocationRepository, chatService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 模拟 JWT 过滤器放入 SecurityContext 的认证主体（CurrentUser 的读取来源） */
    private static void loginAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(userId, "user" + userId, "用户" + userId, Set.of()),
                        null, List.of()));
    }

    @Test
    void 本人查询正常返回分页() {
        AgentSession session = chatService.createSession("1");
        when(invocationRepository.findBySession(anyString(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
        when(invocationRepository.sumTokens(anyString()))
                .thenReturn(new TokenSummary(0, 0));

        loginAs(1L);
        InvocationPageResponse response = controller.list(session.getSessionId(), 1, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
    }

    @Test
    void 他人查询按会话不存在处理_不触达调用记录() {
        AgentSession session = chatService.createSession("1");

        loginAs(2L);
        assertThatThrownBy(() -> controller.list(session.getSessionId(), 1, 20))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("会话不存在: " + session.getSessionId());

        // 归属校验在查询调用记录之前，越权方拿不到任何调用链数据
        verifyNoInteractions(invocationRepository);
    }

    @Test
    void 会话不存在同样404() {
        loginAs(1L);
        assertThatThrownBy(() -> controller.list("no-such-session", 1, 20))
                .isInstanceOf(SessionNotFoundException.class)
                .hasMessage("会话不存在: no-such-session");
        verifyNoInteractions(invocationRepository);
    }

    @Test
    void 参数校验仍先于归属校验_空sessionId为400语义() {
        loginAs(1L);
        assertThatThrownBy(() -> controller.list(" ", 1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId 不能为空");
    }
}
