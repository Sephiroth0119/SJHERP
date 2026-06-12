package com.sjherp.app.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.sjherp.agent.history.HistoryTrimmer;
import com.sjherp.agent.history.LlmHistorySummarizer;
import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.loop.AgentInvocationListener;
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
import com.sjherp.infra.llm.OpenAiCompatibleLlmClient;

/**
 * 聊天 Agent 装配：按 sjherp.agent.mode 在 LlmAgent 与 PlaceholderAgent 间切换。
 *
 * <ul>
 *   <li>auto（默认）：chat 角色的 provider 配置了 api-key → LlmAgent；否则回退
 *       PlaceholderAgent 并打 WARN；</li>
 *   <li>llm：强制 LlmAgent，chat 角色缺 api-key 时启动失败（快速暴露配置错误）；</li>
 *   <li>placeholder：强制规则占位 Agent（演示/离线开发用）。</li>
 * </ul>
 *
 * <p>多 LLM provider（M1-T07）：LlmClient 实例按 Agent 角色（sjherp.llm.roles）
 * 解析 provider 构建——chat 角色给对话主链路（AgentLoop）、summarizer 角色给历史
 * 摘要；同一 provider 只实例化一次（同 provider 同实例）。所有 provider 均为
 * OpenAI 兼容协议（{@link OpenAiCompatibleLlmClient}），切换模型只改配置不改代码。
 *
 * <p>M1-T02 起 LlmAgent 基于 AgentLoop 执行循环：LLM 客户端、参数编解码
 * （Jackson）、JSON Schema 校验、权限校验（M2-T06 起为基于用户角色的
 * RolePermissionToolChecker）在此注入；ToolRegistry 为空时循环行为退化为单轮对话。
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
                           @Value("${sjherp.agent.history-token-budget:8000}") int historyTokenBudget,
                           @Value("${sjherp.agent.keep-recent-rounds:6}") int keepRecentRounds,
                           PlaceholderAgent placeholderAgent,
                           AgentReplyJsonCodec codec,
                           PendingToolCallJsonCodec pendingCodec,
                           ToolRegistry toolRegistry,
                           AgentInvocationListener invocationListener,
                           ToolPermissionChecker permissionChecker) {
        String normalized = mode == null ? "auto" : mode.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "placeholder" -> {
                log.info("聊天 Agent：按配置使用 PlaceholderAgent（sjherp.agent.mode=placeholder）");
                yield placeholderAgent;
            }
            case "llm" -> {
                if (!llm.roleHasApiKey(LlmProperties.ROLE_CHAT)) {
                    throw new IllegalStateException("sjherp.agent.mode=llm 但 chat 角色的 provider（"
                            + llm.providerNameForRole(LlmProperties.ROLE_CHAT) + "）未配置 api-key"
                            + "（设置环境变量 SJHERP_LLM_API_KEY 或启用 local profile）");
                }
                yield llmAgent(llm, codec, pendingCodec, toolRegistry, invocationListener,
                        permissionChecker, parseFinalJsonMode(finalJsonMode), maxIterations,
                        loopTimeoutSeconds, historyTokenBudget, keepRecentRounds);
            }
            case "auto" -> {
                if (llm.roleHasApiKey(LlmProperties.ROLE_CHAT)) {
                    yield llmAgent(llm, codec, pendingCodec, toolRegistry, invocationListener,
                            permissionChecker, parseFinalJsonMode(finalJsonMode), maxIterations,
                            loopTimeoutSeconds, historyTokenBudget, keepRecentRounds);
                }
                log.warn("chat 角色的 provider 未配置 api-key，聊天回退到 PlaceholderAgent（规则占位演示模式）。"
                        + "设置环境变量 SJHERP_LLM_API_KEY 或启用 local profile 以启用 LLM");
                yield placeholderAgent;
            }
            default -> throw new IllegalStateException(
                    "非法的 sjherp.agent.mode: " + mode + "（可选值：auto / llm / placeholder）");
        };
    }

    private Agent llmAgent(LlmProperties llm, AgentReplyJsonCodec codec,
                           PendingToolCallJsonCodec pendingCodec, ToolRegistry toolRegistry,
                           AgentInvocationListener invocationListener,
                           ToolPermissionChecker permissionChecker,
                           FinalJsonMode finalJsonMode, int maxIterations, long loopTimeoutSeconds,
                           int historyTokenBudget, int keepRecentRounds) {
        // 按角色构建/复用 LlmClient（M1-T07）：同 provider 同实例（HttpClient 连接池共享）
        Map<String, LlmClient> clientsByProvider = new HashMap<>();
        LlmClient chatClient = clientForRole(llm, LlmProperties.ROLE_CHAT, clientsByProvider);
        LlmClient summarizerClient = clientForRole(llm, LlmProperties.ROLE_SUMMARIZER, clientsByProvider);
        // checker 角色（M6 检查 Agent）此处不实例化，仅占配置位——接入时按同样方式解析

        // 注意：工具按请求时从 ToolRegistry 实时读取，此处不打印数量（演示工具等可能在本 Bean 之后注册）
        log.info("聊天 Agent：使用 LlmAgent + AgentLoop（chatProvider={}, summarizerProvider={}, "
                        + "finalJsonMode={}, maxIterations={}, loopTimeout={}s, "
                        + "historyTokenBudget={}, keepRecentRounds={}）",
                llm.providerNameForRole(LlmProperties.ROLE_CHAT),
                llm.providerNameForRole(LlmProperties.ROLE_SUMMARIZER),
                finalJsonMode, maxIterations, loopTimeoutSeconds, historyTokenBudget, keepRecentRounds);
        // 执行循环（M1-T02/T03）：参数编解码 + JSON Schema 校验 + 权限校验
        // （M2-T06 起为 RolePermissionToolChecker 真实实现，AgentInfraConfig 装配）经接口注入；
        // 调用观测 listener（M1-T06）：每次 LLM 调用与工具调用落 agent_invocation 表，
        // 终轮单独 JSON 调用也在 AgentLoop 内发起，同样会被记录
        AgentLoop agentLoop = new AgentLoop(chatClient, new JacksonToolArgumentsCodec(),
                new JsonSchemaToolArgumentValidator(), permissionChecker,
                invocationListener);
        // 会话上下文治理（M1-T05）：历史超预算时最旧若干轮压缩为摘要，
        // 摘要由 summarizer 角色的 LlmClient 单独调一次生成（低温度），失败时硬截断兜底不阻塞对话；
        // 摘要调用同样落 agent_invocation 观测（M1-T07，purpose=summarize）
        return new LlmAgent(agentLoop, codec, pendingCodec, toolRegistry,
                finalJsonMode, maxIterations, Duration.ofSeconds(loopTimeoutSeconds),
                new HistoryTrimmer(historyTokenBudget, keepRecentRounds),
                new LlmHistorySummarizer(summarizerClient, invocationListener));
    }

    /**
     * 角色 → LlmClient：解析 provider 名（缺角色回落 default-provider）并构建客户端，
     * 同 provider 复用同一实例。provider 缺 api-key 时此处启动失败（fail-fast，
     * 异常消息含 provider 名，见 {@link OpenAiCompatibleLlmClient} 构造校验）。
     */
    private static LlmClient clientForRole(LlmProperties llm, String role,
                                           Map<String, LlmClient> clientsByProvider) {
        String providerName = llm.providerNameForRole(role);
        LlmProperties.ProviderConfig config = llm.providers().get(providerName);
        if (config == null) {
            // 防御：roles 指向未定义 provider 已在 LlmProperties 绑定期拦截，此处只剩 providers 为空的情形
            throw new IllegalStateException("角色 " + role + " 解析到的 provider " + providerName
                    + " 未在 sjherp.llm.providers 中定义");
        }
        return clientsByProvider.computeIfAbsent(providerName, name -> {
            log.info("LLM provider 实例化（provider={}, model={}, baseUrl={}, temperature={}, timeout={}s）",
                    name, config.model(), config.baseUrl(), config.temperature(), config.timeoutSeconds());
            return new OpenAiCompatibleLlmClient(name, config.apiKey(), config.baseUrl(),
                    config.model(), config.temperature(), Duration.ofSeconds(config.timeoutSeconds()));
        });
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
