package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.agent.session.AgentSessionRepository;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.persistence.JdbcAgentSessionRepository;

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

    /** 会话仓储：MySQL 实现为运行时默认 */
    @Bean
    public AgentSessionRepository agentSessionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAgentSessionRepository(jdbcTemplate);
    }
}
