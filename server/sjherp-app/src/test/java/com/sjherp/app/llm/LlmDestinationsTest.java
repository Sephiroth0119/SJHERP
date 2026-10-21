package com.sjherp.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmDestinationsTest {

    @Test
    void 公网https视为第三方() {
        assertThat(LlmDestinations.isThirdParty("https://api.deepseek.com")).isTrue();
        assertThat(LlmDestinations.isThirdParty("https://dashscope.aliyuncs.com/compatible-mode/v1")).isTrue();
    }

    @Test
    void 本机与内网不视为第三方() {
        assertThat(LlmDestinations.isThirdParty("http://localhost:11434")).isFalse();
        assertThat(LlmDestinations.isThirdParty("http://127.0.0.1:11434/v1")).isFalse();
        assertThat(LlmDestinations.isThirdParty("http://192.168.1.10:11434")).isFalse();
        assertThat(LlmDestinations.isThirdParty("http://ollama.local:11434")).isFalse();
    }
}
