package com.sjherp.app.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 接入配置（前缀 sjherp.llm，M1-T07 多 provider 配置化）。
 *
 * <p>结构（2026-06 干净迁移，原平铺结构 sjherp.llm.api-key/base-url/... 已废弃）：
 * <ul>
 *   <li>{@code providers}（map）：provider 名 → 连接配置。所有 provider 走 OpenAI 兼容协议
 *       （DeepSeek / 通义 compatible-mode / Kimi / GPT 共用 OpenAiCompatibleLlmClient 一套实现），
 *       切换/新增模型只改配置不改代码；</li>
 *   <li>{@code roles}（map）：Agent 角色 → provider 名（角色见 {@link #ROLE_CHAT} 等常量）；
 *       未配置的角色回落到 {@code default-provider}；</li>
 *   <li>{@code default-provider}：缺省 provider 名，默认 deepseek。</li>
 * </ul>
 *
 * <p>api-key 一律来自环境变量引用（如 ${SJHERP_LLM_API_KEY:}）或 local profile
 * （application-local.yml，已被 .gitignore 忽略），绝不写进任何会被 git 跟踪的文件。
 *
 * <p>校验在规范构造器中完成（绑定期即启动期 fail-fast）：roles / default-provider
 * 指向未定义的 provider 名、provider 缺 base-url / model 都直接启动失败并报出清晰错误。
 * providers 为空视为「LLM 未配置」（合法，聊天回退占位 Agent），此时不做指向校验。
 *
 * @param defaultProvider 缺省 provider 名（roles 未覆盖的角色回落到它）
 * @param providers       provider 名 → 连接配置
 * @param roles           Agent 角色 → provider 名
 */
@ConfigurationProperties(prefix = "sjherp.llm")
public record LlmProperties(
        @DefaultValue("deepseek") String defaultProvider,
        Map<String, ProviderConfig> providers,
        Map<String, String> roles) {

    /** 角色：对话主链路（LlmAgent + AgentLoop） */
    public static final String ROLE_CHAT = "chat";
    /** 角色：会话历史摘要（LlmHistorySummarizer，M1-T05） */
    public static final String ROLE_SUMMARIZER = "summarizer";
    /** 角色：数据一致性检查 Agent（M6 接入，先留配置位） */
    public static final String ROLE_CHECKER = "checker";

    public LlmProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        roles = roles == null ? Map.of() : Map.copyOf(roles);
        if (providers.isEmpty()) {
            // LLM 未配置：合法状态（聊天回退 PlaceholderAgent），但 roles 不应有内容
            if (!roles.isEmpty()) {
                throw new IllegalStateException("配置了 sjherp.llm.roles=" + roles
                        + " 但 sjherp.llm.providers 为空：请先定义 provider");
            }
        } else {
            // 启动期 fail-fast：指向未定义 provider / provider 缺必填项，报清晰错误
            if (defaultProvider == null || defaultProvider.isBlank()
                    || !providers.containsKey(defaultProvider)) {
                throw new IllegalStateException("sjherp.llm.default-provider=" + defaultProvider
                        + " 未在 sjherp.llm.providers 中定义（已定义的 provider：" + providers.keySet() + "）");
            }
            for (Map.Entry<String, String> role : roles.entrySet()) {
                if (!providers.containsKey(role.getValue())) {
                    throw new IllegalStateException("sjherp.llm.roles." + role.getKey() + "="
                            + role.getValue() + " 指向未定义的 provider（已定义的 provider："
                            + providers.keySet() + "）");
                }
            }
            for (Map.Entry<String, ProviderConfig> provider : providers.entrySet()) {
                ProviderConfig config = provider.getValue();
                if (config.baseUrl() == null || config.baseUrl().isBlank()) {
                    throw new IllegalStateException("sjherp.llm.providers." + provider.getKey()
                            + ".base-url 不能为空");
                }
                if (config.model() == null || config.model().isBlank()) {
                    throw new IllegalStateException("sjherp.llm.providers." + provider.getKey()
                            + ".model 不能为空");
                }
            }
        }
    }

    /**
     * 单个 provider 的连接配置（OpenAI 兼容协议）。
     *
     * @param apiKey         API Key（环境变量引用或 local profile，空 = 未配置）
     * @param baseUrl        API 根地址（必填，如 https://api.deepseek.com）
     * @param model          模型名（必填）
     * @param temperature    默认采样温度（模型参数，非金额/数量，可用 double）
     * @param timeoutSeconds 单次请求整体超时（秒）
     */
    public record ProviderConfig(
            String apiKey,
            String baseUrl,
            String model,
            @DefaultValue("0.7") double temperature,
            @DefaultValue("60") long timeoutSeconds) {

        /** api-key 是否已配置 */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** 角色解析出的 provider 名：roles 未覆盖时回落 default-provider */
    public String providerNameForRole(String role) {
        String name = roles.get(role);
        return name != null ? name : defaultProvider;
    }

    /** 角色解析出的 provider 配置；providers 为空（LLM 未配置）时返回 null */
    public ProviderConfig providerForRole(String role) {
        String name = providerNameForRole(role);
        // name 为 null 仅在 providers 为空且 default-provider 未配置时出现（LLM 未配置）
        return name == null ? null : providers.get(name);
    }

    /** 角色对应的 provider 是否已配置 api-key（providers 为空时为 false） */
    public boolean roleHasApiKey(String role) {
        ProviderConfig config = providerForRole(role);
        return config != null && config.hasApiKey();
    }
}
