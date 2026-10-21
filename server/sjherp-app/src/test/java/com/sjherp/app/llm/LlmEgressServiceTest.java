package com.sjherp.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sjherp.app.config.LlmProperties;
import com.sjherp.app.config.LlmProperties.ProviderConfig;

class LlmEgressServiceTest {

    @Test
    void 第三方deepseek生成告知() {
        LlmProperties llm = new LlmProperties(
                "deepseek",
                Map.of("deepseek", new ProviderConfig("sk-test", "https://api.deepseek.com",
                        "deepseek-chat", 0.7, 60)),
                Map.of("chat", "deepseek"));
        LlmEgressService service = new LlmEgressService(
                llm, new LlmEgressProperties(true, false, false), "auto");
        LlmEgressNotice notice = service.current();
        assertThat(notice.enabled()).isTrue();
        assertThat(notice.thirdParty()).isTrue();
        assertThat(notice.notice()).contains("第三方模型").contains("api.deepseek.com");
        assertThat(notice.redactEnabled()).isFalse();
    }

    @Test
    void 占位模式不提示出境() {
        LlmProperties llm = new LlmProperties("deepseek", Map.of(), Map.of());
        LlmEgressService service = new LlmEgressService(
                llm, new LlmEgressProperties(true, false, false), "placeholder");
        LlmEgressNotice notice = service.current();
        assertThat(notice.enabled()).isFalse();
        assertThat(notice.thirdParty()).isFalse();
        assertThat(notice.notice()).isNull();
    }

    @Test
    void 本地ollama不提示出境() {
        LlmProperties llm = new LlmProperties(
                "ollama",
                Map.of("ollama", new ProviderConfig("sk-local", "http://127.0.0.1:11434/v1",
                        "qwen", 0.7, 60)),
                Map.of("chat", "ollama"));
        LlmEgressService service = new LlmEgressService(
                llm, new LlmEgressProperties(true, true, true), "auto");
        LlmEgressNotice notice = service.current();
        assertThat(notice.enabled()).isTrue();
        assertThat(notice.thirdParty()).isFalse();
        assertThat(notice.notice()).isNull();
        assertThat(notice.redactEnabled()).isTrue();
        assertThat(notice.localOnly()).isTrue();
    }
}
