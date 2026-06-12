package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * LlmProperties 配置绑定单测（M1-T07）：多 provider 多角色解析、缺角色回落
 * default-provider、未知 provider 名绑定期（即启动期）fail-fast 且报清晰错误、
 * providers 为空的占位 Agent 回退语义。
 */
class LlmPropertiesTest {

    /** 用 Spring Binder 模拟启动期的 @ConfigurationProperties 绑定 */
    private static LlmProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("sjherp.llm", Bindable.of(LlmProperties.class))
                .orElseGet(() -> new LlmProperties(null, null, null));
    }

    /** 两个 provider + 部分角色的典型多模型配置 */
    private static Map<String, String> multiProviderConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("sjherp.llm.default-provider", "deepseek");
        props.put("sjherp.llm.providers.deepseek.base-url", "https://api.deepseek.com");
        props.put("sjherp.llm.providers.deepseek.model", "deepseek-chat");
        props.put("sjherp.llm.providers.deepseek.api-key", "sk-deepseek");
        props.put("sjherp.llm.providers.qwen.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        props.put("sjherp.llm.providers.qwen.model", "qwen-plus");
        props.put("sjherp.llm.providers.qwen.api-key", "sk-qwen");
        props.put("sjherp.llm.providers.qwen.temperature", "0.3");
        props.put("sjherp.llm.providers.qwen.timeout-seconds", "90");
        props.put("sjherp.llm.roles.chat", "deepseek");
        props.put("sjherp.llm.roles.checker", "qwen");
        return props;
    }

    @Test
    void multiProviderMultiRoleBindingResolvesEachRole() {
        LlmProperties llm = bind(multiProviderConfig());

        assertThat(llm.providers()).containsOnlyKeys("deepseek", "qwen");
        // 显式角色：各自解析到指定 provider
        assertThat(llm.providerNameForRole(LlmProperties.ROLE_CHAT)).isEqualTo("deepseek");
        assertThat(llm.providerNameForRole(LlmProperties.ROLE_CHECKER)).isEqualTo("qwen");
        assertThat(llm.providerForRole(LlmProperties.ROLE_CHECKER).model()).isEqualTo("qwen-plus");
        assertThat(llm.providerForRole(LlmProperties.ROLE_CHECKER).temperature()).isEqualTo(0.3);
        assertThat(llm.providerForRole(LlmProperties.ROLE_CHECKER).timeoutSeconds()).isEqualTo(90);
        // 默认值：未配置 temperature / timeout-seconds 时回落 0.7 / 60
        assertThat(llm.providerForRole(LlmProperties.ROLE_CHAT).temperature()).isEqualTo(0.7);
        assertThat(llm.providerForRole(LlmProperties.ROLE_CHAT).timeoutSeconds()).isEqualTo(60);
        assertThat(llm.roleHasApiKey(LlmProperties.ROLE_CHAT)).isTrue();
    }

    @Test
    void missingRoleFallsBackToDefaultProvider() {
        LlmProperties llm = bind(multiProviderConfig());

        // summarizer 角色未配置 → 回落 default-provider（deepseek）
        assertThat(llm.providerNameForRole(LlmProperties.ROLE_SUMMARIZER)).isEqualTo("deepseek");
        assertThat(llm.providerForRole(LlmProperties.ROLE_SUMMARIZER).model()).isEqualTo("deepseek-chat");
        // 任意未知角色同样回落（角色集合可扩展，不在配置层硬编码枚举）
        assertThat(llm.providerNameForRole("developer")).isEqualTo("deepseek");
    }

    @Test
    void roleReferencingUnknownProviderFailsFastWithClearMessage() {
        Map<String, String> props = multiProviderConfig();
        props.put("sjherp.llm.roles.summarizer", "no-such-provider");

        assertThatThrownBy(() -> bind(props))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sjherp.llm.roles.summarizer=no-such-provider")
                .hasMessageContaining("未定义的 provider")
                .hasMessageContaining("deepseek");
    }

    @Test
    void unknownDefaultProviderFailsFast() {
        Map<String, String> props = multiProviderConfig();
        props.put("sjherp.llm.default-provider", "ghost");

        assertThatThrownBy(() -> bind(props))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sjherp.llm.default-provider=ghost");
    }

    @Test
    void providerMissingBaseUrlOrModelFailsFast() {
        Map<String, String> noBaseUrl = new HashMap<>();
        noBaseUrl.put("sjherp.llm.default-provider", "deepseek");
        noBaseUrl.put("sjherp.llm.providers.deepseek.model", "deepseek-chat");
        assertThatThrownBy(() -> bind(noBaseUrl))
                .rootCause()
                .hasMessageContaining("sjherp.llm.providers.deepseek.base-url 不能为空");

        Map<String, String> noModel = new HashMap<>();
        noModel.put("sjherp.llm.default-provider", "deepseek");
        noModel.put("sjherp.llm.providers.deepseek.base-url", "https://api.deepseek.com");
        assertThatThrownBy(() -> bind(noModel))
                .rootCause()
                .hasMessageContaining("sjherp.llm.providers.deepseek.model 不能为空");
    }

    @Test
    void rolesWithoutProvidersFailsFast() {
        Map<String, String> props = new HashMap<>();
        props.put("sjherp.llm.roles.chat", "deepseek");

        assertThatThrownBy(() -> bind(props))
                .rootCause()
                .hasMessageContaining("providers 为空");
    }

    @Test
    void emptyProvidersMeansLlmNotConfigured() {
        // 完全未配置 sjherp.llm：合法（聊天回退 PlaceholderAgent），不做指向校验
        LlmProperties llm = bind(Map.of());

        assertThat(llm.providers()).isEmpty();
        assertThat(llm.providerForRole(LlmProperties.ROLE_CHAT)).isNull();
        assertThat(llm.roleHasApiKey(LlmProperties.ROLE_CHAT)).isFalse();
    }

    @Test
    void blankApiKeyMeansNotConfigured() {
        Map<String, String> props = multiProviderConfig();
        // 环境变量未设置时占位符展开为空串：视为未配置 api-key（auto 模式回退占位 Agent）
        props.put("sjherp.llm.providers.deepseek.api-key", "");
        LlmProperties llm = bind(props);

        assertThat(llm.roleHasApiKey(LlmProperties.ROLE_CHAT)).isFalse();
        assertThat(llm.roleHasApiKey(LlmProperties.ROLE_CHECKER)).isTrue();
    }
}
