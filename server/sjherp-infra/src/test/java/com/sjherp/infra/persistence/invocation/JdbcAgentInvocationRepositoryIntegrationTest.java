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

    @Test
    void 时间窗汇总_只统计窗内LLM行并按模型拆分() {
        String sessionId = "it-llm-daily-" + uniqueSuffix();
        Instant t0 = Instant.parse("2026-10-20T00:00:00Z");
        Instant t1 = Instant.parse("2026-10-21T00:00:00Z");
        Instant t2 = Instant.parse("2026-10-22T00:00:00Z");
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.LLM, "deepseek-chat", null, 100L,
                10, 4, true, null, t1.plusSeconds(60)));
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.LLM, "deepseek-chat", null, 200L,
                20, 6, false, "{\"error\":\"timeout\"}", t1.plusSeconds(120)));
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.LLM, "qwen-plus", null, 50L,
                5, 1, true, null, t1.plusSeconds(180)));
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.TOOL, null, "create_customer", 10L,
                null, null, true, null, t1.plusSeconds(90)));
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.LLM, "deepseek-chat", null, 999L,
                999, 999, true, null, t0.plusSeconds(1)));
        invocationRepository.insert(new AgentInvocation(null, sessionId,
                AgentInvocationType.LLM, "deepseek-chat", null, 1L,
                1, 1, true, null, t2));

        AgentInvocationRepository.LlmUsageWindow window = invocationRepository.summarizeLlm(t1, t2);
        assertThat(window.llmCalls()).isEqualTo(3);
        assertThat(window.llmFailures()).isEqualTo(1);
        assertThat(window.promptTokens()).isEqualTo(35);
        assertThat(window.completionTokens()).isEqualTo(11);
        assertThat(window.durationMs()).isEqualTo(350);
        assertThat(window.byModel()).hasSize(2);
        assertThat(window.byModel().get(0).model()).isEqualTo("deepseek-chat");
        assertThat(window.byModel().get(0).calls()).isEqualTo(2);
        assertThat(window.byModel().get(0).failures()).isEqualTo(1);
        assertThat(window.byModel().get(1).model()).isEqualTo("qwen-plus");
        assertThat(window.byModel().get(1).calls()).isEqualTo(1);
    }
}
