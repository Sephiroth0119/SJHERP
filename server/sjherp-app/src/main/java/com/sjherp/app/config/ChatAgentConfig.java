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

import com.sjherp.agent.loop.AgentLoop;
import com.sjherp.agent.loop.FinalJsonMode;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.chat.Agent;
import com.sjherp.app.chat.LlmAgent;
import com.sjherp.app.chat.PlaceholderAgent;
import com.sjherp.infra.agent.AgentReplyJsonCodec;
import com.sjherp.infra.agent.JacksonToolArgumentsCodec;
import com.sjherp.infra.agent.JsonSchemaToolArgumentValidator;
import com.sjherp.infra.agent.PendingToolCallJsonCodec;
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
 * <p>M1-T02 起 LlmAgent 基于 AgentLoop 执行循环：LLM 客户端、参数编解码
 * （Jackson）、JSON Schema 校验、权限校验（本期 allowAll 占位，M2-T06 接真实
 * 权限）在此注入；ToolRegistry 为空时循环行为退化为单轮对话。
 *
 * <p>终轮 JSON 模式（sjherp.agent.final-json-mode）：DeepSeek 实测（2026-06）
 * response_format=json_object 与 tools 同时携带不报错、但模型稳定不发起工具调用，
 * 故默认 separate-final-call（工具轮不带 json_object，终轮单独调一次）。
 *
 * <p>infra 的实现类不加 Spring 注解，在此显式装配（与 AgentInfraConfig 同约定）。
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
                           @Value("${sjherp.agent.final-json-mode:separate-final-call}") String finalJsonMode,
                           @Value("${sjherp.agent.max-iterations:8}") int maxIterations,
                           @Value("${sjherp.agent.loop-timeout-seconds:300}") long loopTimeoutSeconds,
                           PlaceholderAgent placeholderAgent,
                           AgentReplyJsonCodec codec,
                           PendingToolCallJsonCodec pendingCodec,
                           ToolRegistry toolRegistry) {
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
                yield llmAgent(llm, codec, pendingCodec, toolRegistry,
                        parseFinalJsonMode(finalJsonMode), maxIterations, loopTimeoutSeconds);
            }
            case "auto" -> {
                if (llm.hasApiKey()) {
                    yield llmAgent(llm, codec, pendingCodec, toolRegistry,
                            parseFinalJsonMode(finalJsonMode), maxIterations, loopTimeoutSeconds);
                }
                log.warn("未配置 sjherp.llm.api-key，聊天回退到 PlaceholderAgent（规则占位演示模式）。"
                        + "设置环境变量 SJHERP_LLM_API_KEY 或启用 local profile 以启用 LLM");
                yield placeholderAgent;
            }
            default -> throw new IllegalStateException(
                    "非法的 sjherp.agent.mode: " + mode + "（可选值：auto / llm / placeholder）");
        };
    }

    private Agent llmAgent(LlmProperties llm, AgentReplyJsonCodec codec,
                           PendingToolCallJsonCodec pendingCodec, ToolRegistry toolRegistry,
                           FinalJsonMode finalJsonMode, int maxIterations, long loopTimeoutSeconds) {
        // 注意：工具按请求时从 ToolRegistry 实时读取，此处不打印数量（演示工具等可能在本 Bean 之后注册）
        log.info("聊天 Agent：使用 LlmAgent + AgentLoop（provider=DeepSeek, model={}, baseUrl={}, "
                        + "timeout={}s, finalJsonMode={}, maxIterations={}, loopTimeout={}s）",
                llm.model(), llm.baseUrl(), llm.timeoutSeconds(), finalJsonMode,
                maxIterations, loopTimeoutSeconds);
        DeepSeekLlmClient client = new DeepSeekLlmClient(
                llm.apiKey(), llm.baseUrl(), llm.model(), llm.temperature(),
                Duration.ofSeconds(llm.timeoutSeconds()));
        // 执行循环（M1-T02/T03）：参数编解码 + JSON Schema 校验 + 权限校验（占位）经接口注入
        AgentLoop agentLoop = new AgentLoop(client, new JacksonToolArgumentsCodec(),
                new JsonSchemaToolArgumentValidator(), ToolPermissionChecker.allowAll());
        return new LlmAgent(agentLoop, codec, pendingCodec, toolRegistry,
                finalJsonMode, maxIterations, Duration.ofSeconds(loopTimeoutSeconds));
    }

    /** 配置值 → FinalJsonMode（with-tools / separate-final-call） */
    private static FinalJsonMode parseFinalJsonMode(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "with-tools" -> FinalJsonMode.JSON_WITH_TOOLS;
            case "", "separate-final-call" -> FinalJsonMode.JSON_SEPARATE_FINAL_CALL;
            default -> throw new IllegalStateException("非法的 sjherp.agent.final-json-mode: " + value
                    + "（可选值：separate-final-call / with-tools）");
        };
    }
}
