package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.agent.loop.AgentInvocationListener;
import com.sjherp.agent.session.AgentSessionRepository;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.agent.PendingToolCallJsonCodec;
import com.sjherp.infra.persistence.JdbcAgentSessionRepository;
import com.sjherp.infra.persistence.invocation.AgentInvocationRepository;
import com.sjherp.infra.persistence.invocation.JdbcAgentInvocationRepository;
import com.sjherp.infra.persistence.invocation.PersistingAgentInvocationListener;

/**
 * Agent 框架基础设施装配。
 *
 * <p>infra 模块的实现类不加 Spring 注解（保持可独立测试），统一在此显式装配。
 * 会话仓储默认使用 MySQL 实现（ADR-001：任意时刻杀进程会话可恢复）；
 * InMemoryAgentSessionRepository 仅保留给单元测试，不再注册为 Bean。
 */
@Configuration
public class AgentInfraConfig {

    /** AgentReply 协议 JSON 编解码（Jackson 只出现在 infra/app，agent 模块零依赖） */
    @Bean
    public AgentReplyJsonCodec agentReplyJsonCodec() {
        return new AgentReplyJsonCodec();
    }

    /** 高风险待确认调用现场（PendingToolCall）的 JSON 编解码（M1-T03） */
    @Bean
    public PendingToolCallJsonCodec pendingToolCallJsonCodec() {
        return new PendingToolCallJsonCodec();
    }

    /** 会话仓储：MySQL 实现为运行时默认 */
    @Bean
    public AgentSessionRepository agentSessionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAgentSessionRepository(jdbcTemplate);
    }

    /** Agent 调用观测记录仓储（M1-T06，V7 迁移 agent_invocation 表） */
    @Bean
    public AgentInvocationRepository agentInvocationRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAgentInvocationRepository(jdbcTemplate);
    }

    /** 调用观测落库 listener（M1-T06）：由 ChatAgentConfig 装配进 AgentLoop */
    @Bean
    public AgentInvocationListener agentInvocationListener(AgentInvocationRepository repository) {
        return new PersistingAgentInvocationListener(repository);
    }
}
