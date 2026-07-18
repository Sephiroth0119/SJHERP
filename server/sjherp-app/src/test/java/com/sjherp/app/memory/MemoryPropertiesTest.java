package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

class MemoryPropertiesTest {

    @Test
    void 默认关闭时不要求本地服务配置() {
        MemoryProperties properties = MemoryProperties.disabled();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.embedding().model()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(properties.embedding().dimension()).isEqualTo(1024);
        assertThat(properties.vector().distance()).isEqualTo("COSINE");
    }

    @Test
    void 召回配置提供安全默认值() {
        MemoryProperties.Recall recall = MemoryProperties.disabled().recall();

        assertThat(recall.candidateLimit()).isEqualTo(12);
        assertThat(recall.maxResults()).isEqualTo(5);
        assertThat(recall.minScore()).isEqualTo(0.45d);
        assertThat(recall.maxContextChars()).isEqualTo(6000);
    }

    @Test
    void 启用时拒绝非法召回边界() {
        MemoryProperties enabled = enabledProperties(1024, "COSINE");

        assertThatThrownBy(() -> new MemoryProperties(true, enabled.embedding(),
                enabled.vector(), enabled.indexing(),
                new MemoryProperties.Recall(4, 5, 0.45d, 6000)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MemoryProperties(true, enabled.embedding(),
                enabled.vector(), enabled.indexing(),
                new MemoryProperties.Recall(12, 5, Double.NaN, 6000)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MemoryProperties(true, enabled.embedding(),
                enabled.vector(), enabled.indexing(),
                new MemoryProperties.Recall(201, 5, 0.45d, 6000)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MemoryProperties(true, enabled.embedding(),
                enabled.vector(), enabled.indexing(),
                new MemoryProperties.Recall(12, 21, 0.45d, 6000)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MemoryProperties(true, enabled.embedding(),
                enabled.vector(), enabled.indexing(),
                new MemoryProperties.Recall(12, 5, 0.45d, 999)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 启用时接受锁定的本地技术规格() {
        MemoryProperties properties = enabledProperties(1024, "COSINE");

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.vector().collection())
                .isEqualTo("sjherp-memory-qwen3-0_6b-1024-v1");
    }

    @Test
    void 启用时拒绝非1024维或非Cosine() {
        assertThatThrownBy(() -> enabledProperties(768, "COSINE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1024");
        assertThatThrownBy(() -> enabledProperties(1024, "DOT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COSINE");
    }

    @Test
    void 启用时拒绝外部提供商和越界重试参数() {
        MemoryProperties.Embedding external = new MemoryProperties.Embedding(
                "openai", URI.create("https://api.example.com"), "model", 1024, 60);
        MemoryProperties.Vector vector = new MemoryProperties.Vector(
                "qdrant", URI.create("http://localhost:6333"), "memory-v1", "COSINE");

        assertThatThrownBy(() -> new MemoryProperties(true, external, vector,
                new MemoryProperties.Indexing(30, 50, 8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ollama");
        assertThatThrownBy(() -> new MemoryProperties(true,
                enabledProperties(1024, "COSINE").embedding(), vector,
                new MemoryProperties.Indexing(30, 501, 101)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MemoryProperties enabledProperties(int dimension, String distance) {
        return new MemoryProperties(true,
                new MemoryProperties.Embedding("ollama", URI.create("http://localhost:11434"),
                        "qwen3-embedding:0.6b", dimension, 60),
                new MemoryProperties.Vector("qdrant", URI.create("http://localhost:6333"),
                        "sjherp-memory-qwen3-0_6b-1024-v1", distance),
                new MemoryProperties.Indexing(30, 50, 8));
    }
}
