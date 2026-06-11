package com.sjherp.app.config;

import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.sjherp.app.chat.Agent;
import com.sjherp.app.chat.LlmAgent;
import com.sjherp.app.chat.PlaceholderAgent;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.llm.DeepSeekLlmClient;

/**
 * 聊天 Agent 装配：按 sjherp.agent.mode 在 LlmAgent 与 PlaceholderAgent 间切换。
 *
 * <ul>
 *   <li>auto（默认）：sjherp.llm.api-key 非空 → LlmAgent；否则回退 PlaceholderAgent 并打 WARN；</li>
 *   <li>llm：强制 LlmAgent，api-key 缺失时启动失败（快速暴露配置错误）；</li>
 *   <li>placeholder：强制规则占位 Agent（演示/离线开发用）。</li>
 * </ul>
 *
 * <p>infra 的 DeepSeekLlmClient 不加 Spring 注解，在此显式装配（与 AgentInfraConfig 同约定）。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class ChatAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatAgentConfig.class);

    /** 聊天链路使用的 Agent（@Primary：覆盖 PlaceholderAgent 自身的 Bean） */
    @Bean
    @Primary
    public Agent chatAgent(LlmProperties llm,
                           @Value("${sjherp.agent.mode:auto}") String mode,
                           PlaceholderAgent placeholderAgent,
                           AgentReplyJsonCodec codec) {
        String normalized = mode == null ? "auto" : mode.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "placeholder" -> {
                log.info("聊天 Agent：按配置使用 PlaceholderAgent（sjherp.agent.mode=placeholder）");
                yield placeholderAgent;
            }
            case "llm" -> {
                if (!llm.hasApiKey()) {
                    throw new IllegalStateException("sjherp.agent.mode=llm 但未配置 sjherp.llm.api-key"
                            + "（设置环境变量 SJHERP_LLM_API_KEY 或启用 local profile）");
                }
                yield llmAgent(llm, codec);
            }
            case "auto" -> {
                if (llm.hasApiKey()) {
                    yield llmAgent(llm, codec);
                }
                log.warn("未配置 sjherp.llm.api-key，聊天回退到 PlaceholderAgent（规则占位演示模式）。"
                        + "设置环境变量 SJHERP_LLM_API_KEY 或启用 local profile 以启用 LLM");
                yield placeholderAgent;
            }
            default -> throw new IllegalStateException(
                    "非法的 sjherp.agent.mode: " + mode + "（可选值：auto / llm / placeholder）");
        };
    }

    private Agent llmAgent(LlmProperties llm, AgentReplyJsonCodec codec) {
        log.info("聊天 Agent：使用 LlmAgent（provider=DeepSeek, model={}, baseUrl={}, timeout={}s）",
                llm.model(), llm.baseUrl(), llm.timeoutSeconds());
        DeepSeekLlmClient client = new DeepSeekLlmClient(
                llm.apiKey(), llm.baseUrl(), llm.model(), llm.temperature(),
                Duration.ofSeconds(llm.timeoutSeconds()),
                // 聊天链路要求模型输出协议 JSON，启用 json_object
                true);
        return new LlmAgent(client, codec);
    }
}
