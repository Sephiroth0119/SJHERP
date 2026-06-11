package com.sjherp.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.tool.DemoHighRiskTool;
import com.sjherp.app.tool.EchoTool;

/**
 * Agent 工具装配（M1-T02/T03）。
 *
 * <p>ToolRegistry 是 Agent 可用能力的唯一入口：默认空注册表
 * （AgentLoop 行为退化为单轮对话，与接入工具前一致）；
 * 业务工具随各里程碑交付逐步在此类（或各模块自己的配置类）注册。
 */
@Configuration
public class ToolConfig {

    /** 工具注册表（默认空；各业务模块的工具配置向它注册） */
    @Bean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    /**
     * 演示工具注册：仅 dev / local profile 生效（M1 验收链路用，不进生产）。
     * EchoTool（NORMAL，验证普通工具往返）+ DemoHighRiskTool（HIGH，验证高风险
     * 拦截 → 确认卡片 → 恢复执行的完整 Human-in-the-loop 流程）。
     */
    @Configuration
    @Profile({"dev", "local"})
    static class DemoToolConfig {

        private static final Logger log = LoggerFactory.getLogger(DemoToolConfig.class);

        DemoToolConfig(ToolRegistry registry) {
            registry.register(new EchoTool());
            registry.register(new DemoHighRiskTool());
            log.info("已注册演示工具：echo（NORMAL）、demo_post_document（HIGH）——仅 dev/local profile");
        }
    }
}
