package com.sjherp.infra.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.session.AgentMessage;
import com.sjherp.agent.session.AgentSession;
import com.sjherp.agent.session.MessageRole;
import com.sjherp.agent.session.SessionStatus;

/**
 * 会话仓储真实 MySQL 最小往返测试（X-2）：insert → findById/findByUserId，
 * 重点覆盖 pending_tool_call（V3）与 history_summary/summarized_until_seq（V8）列的读写，
 * 以及消息只追加不修改的补插语义。
 */
class JdbcAgentSessionRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcAgentSessionRepository sessionRepository = new JdbcAgentSessionRepository(jdbc);

    @Test
    void 会话_含待确认现场与历史摘要保存后完整恢复() {
        String sessionId = "it-sess-" + uniqueSuffix();
        String userId = "it-user-" + uniqueSuffix();
        AgentSession session = new AgentSession(sessionId, userId);
        session.append(AgentMessage.user("帮我创建客户"));
        session.append(AgentMessage.assistant("{\"version\":\"0.1\",\"text\":\"好的\"}"));
        // V3：高风险待确认现场（JSON 列读写）
        session.setPendingToolCallJson(
                "{\"toolName\":\"create_customer\",\"argumentsJson\":\"{\\\"name\\\":\\\"客户甲\\\"}\"}");
        // V8：历史摘要 + 覆盖位点
        session.updateHistorySummary("- 客户甲订购商品 A，金额 1888.88 元", 2);

        sessionRepository.save(session);

        Optional<AgentSession> found = sessionRepository.findById(sessionId);
        assertThat(found).isPresent();
        AgentSession restored = found.get();
        assertThat(restored.getUserId()).isEqualTo(userId);
        assertThat(restored.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(restored.getMessages()).hasSize(2);
        assertThat(restored.getMessages().get(0).role()).isEqualTo(MessageRole.USER);
        assertThat(restored.getMessages().get(0).content()).isEqualTo("帮我创建客户");
        assertThat(restored.getMessages().get(1).role()).isEqualTo(MessageRole.ASSISTANT);
        // 现场与摘要逐列核对（杀进程/热部署后凭这些列恢复，ADR-001 路线 C）
        assertThat(restored.hasPendingToolCall()).isTrue();
        assertThat(restored.getPendingToolCallJson()).contains("create_customer");
        assertThat(restored.getHistorySummary()).contains("1888.88");
        assertThat(restored.getSummarizedUntilSeq()).isEqualTo(2);

        assertThat(sessionRepository.findByUserId(userId))
                .extracting(AgentSession::getSessionId).containsExactly(sessionId);
    }

    @Test
    void 消息只追加_二次保存补插新消息且清空现场() {
        String sessionId = "it-sess-" + uniqueSuffix();
        AgentSession session = new AgentSession(sessionId, "it-user-append");
        session.append(AgentMessage.user("第一轮"));
        sessionRepository.save(session);

        // 模拟下一轮：恢复会话、追加消息、现场清空（确认流程结束）
        AgentSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        reloaded.append(AgentMessage.assistant("{\"version\":\"0.1\",\"text\":\"第一轮回复\"}"));
        reloaded.setPendingToolCallJson(null);
        sessionRepository.save(reloaded);

        AgentSession again = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(again.getMessages()).hasSize(2);
        assertThat(again.getMessages().get(1).content()).contains("第一轮回复");
        assertThat(again.hasPendingToolCall()).isFalse();
        assertThat(again.getPendingToolCallJson()).isNull();
    }
}
