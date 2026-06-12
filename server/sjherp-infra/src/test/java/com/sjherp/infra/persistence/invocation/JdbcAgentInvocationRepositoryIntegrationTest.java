package com.sjherp.infra.persistence.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.sjherp.domain.common.PageResult;
import com.sjherp.infra.persistence.MySqlContainerTestBase;

/**
 * Agent 调用观测仓储真实 MySQL 最小往返测试（X-2）：
 * insert（LLM 行 + TOOL 行）→ findBySession 倒序分页 → sumTokens 只汇总 LLM 行。
 */
class JdbcAgentInvocationRepositoryIntegrationTest extends MySqlContainerTestBase {

    private final JdbcAgentInvocationRepository invocationRepository =
            new JdbcAgentInvocationRepository(jdbc);

    @Test
    void 调用记录_插入后按会话查询并汇总token() {
        String sessionId = "it-session-" + uniqueSuffix();
        // LLM 行：带 token 与 detail JSON
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.LLM, "deepseek-chat", null, 1234L,
                100, 50, true, "{\"round\":1,\"hasToolCalls\":false}", Instant.now()));
        // TOOL 行：token 为 null（detail JSON 列可空）
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.TOOL, null, "create_customer", 56L,
                null, null, true, null, Instant.now().plusMillis(10)));

        PageResult<AgentInvocation> page = invocationRepository.findBySession(sessionId, 1, 20);
        assertThat(page.total()).isEqualTo(2);
        // created_at 倒序：TOOL 行（更晚）在前
        assertThat(page.items().get(0).type()).isEqualTo(AgentInvocationType.TOOL);
        assertThat(page.items().get(0).toolName()).isEqualTo("create_customer");
        assertThat(page.items().get(0).promptTokens()).isNull();
        assertThat(page.items().get(1).type()).isEqualTo(AgentInvocationType.LLM);
        assertThat(page.items().get(1).model()).isEqualTo("deepseek-chat");
        assertThat(page.items().get(1).detailJson()).contains("\"round\"");

        // token 汇总只统计 type=LLM 的行
        AgentInvocationRepository.TokenSummary summary = invocationRepository.sumTokens(sessionId);
        assertThat(summary.totalPromptTokens()).isEqualTo(100);
        assertThat(summary.totalCompletionTokens()).isEqualTo(50);
    }
}
