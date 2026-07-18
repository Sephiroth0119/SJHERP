package com.sjherp.app.memory;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 大记忆本地基础设施配置；开启时按设计规格 fail-fast 校验。 */
@ConfigurationProperties(prefix = "sjherp.memory")
public record MemoryProperties(boolean enabled, Embedding embedding,
        Vector vector, Indexing indexing, Recall recall) {

    private static final URI DEFAULT_OLLAMA_URL = URI.create("http://localhost:11434");
    private static final URI DEFAULT_QDRANT_URL = URI.create("http://localhost:6333");

    public MemoryProperties(boolean enabled, Embedding embedding,
            Vector vector, Indexing indexing) {
        this(enabled, embedding, vector, indexing, null);
    }

    public MemoryProperties {
        embedding = embedding == null ? defaultEmbedding() : embedding;
        vector = vector == null ? defaultVector() : vector;
        indexing = indexing == null ? defaultIndexing() : indexing;
        recall = recall == null ? defaultRecall() : recall;
        if (enabled) {
            validateEnabled(embedding, vector, indexing, recall);
        }
    }

    /** 默认关闭且带完整安全默认值，关闭态不会触发本地网络连接。 */
    public static MemoryProperties disabled() {
        return new MemoryProperties(false, null, null, null);
    }

    private static Embedding defaultEmbedding() {
        return new Embedding("ollama", DEFAULT_OLLAMA_URL,
                "qwen3-embedding:0.6b", 1024, 60);
    }

    private static Vector defaultVector() {
        return new Vector("qdrant", DEFAULT_QDRANT_URL,
                "sjherp-memory-qwen3-0_6b-1024-v1", "COSINE");
    }

    private static Indexing defaultIndexing() {
        return new Indexing(30, 50, 8);
    }

    private static Recall defaultRecall() {
        return new Recall(12, 5, 0.45d, 6000);
    }

    private static void validateEnabled(Embedding embedding, Vector vector,
            Indexing indexing, Recall recall) {
        if (!"ollama".equalsIgnoreCase(embedding.provider())) {
            throw new IllegalStateException("sjherp.memory.embedding.provider 必须为 ollama");
        }
        requireHttpUri(embedding.baseUrl(), "sjherp.memory.embedding.base-url");
        requireText(embedding.model(), "sjherp.memory.embedding.model");
        if (embedding.dimension() != 1024) {
            throw new IllegalStateException("sjherp.memory.embedding.dimension 必须为 1024");
        }
        if (embedding.timeoutSeconds() < 1) {
            throw new IllegalStateException("sjherp.memory.embedding.timeout-seconds 必须为正数");
        }

        if (!"qdrant".equalsIgnoreCase(vector.provider())) {
            throw new IllegalStateException("sjherp.memory.vector.provider 必须为 qdrant");
        }
        requireHttpUri(vector.baseUrl(), "sjherp.memory.vector.base-url");
        requireText(vector.collection(), "sjherp.memory.vector.collection");
        if (!"COSINE".equalsIgnoreCase(vector.distance())) {
            throw new IllegalStateException("sjherp.memory.vector.distance 必须为 COSINE");
        }

        if (indexing.retryDelaySeconds() < 1) {
            throw new IllegalStateException("sjherp.memory.indexing.retry-delay-seconds 必须为正数");
        }
        if (indexing.batchSize() < 1 || indexing.batchSize() > 500) {
            throw new IllegalStateException("sjherp.memory.indexing.batch-size 必须在 1 到 500 之间");
        }
        if (indexing.maxRetries() < 1 || indexing.maxRetries() > 100) {
            throw new IllegalStateException("sjherp.memory.indexing.max-retries 必须在 1 到 100 之间");
        }

        if (recall.candidateLimit() < 1 || recall.candidateLimit() > 200
                || recall.candidateLimit() < recall.maxResults()) {
            throw new IllegalStateException(
                    "sjherp.memory.recall.candidate-limit 必须在 1 到 200 之间且不小于 max-results");
        }
        if (recall.maxResults() < 1 || recall.maxResults() > 20) {
            throw new IllegalStateException("sjherp.memory.recall.max-results 必须在 1 到 20 之间");
        }
        if (!Double.isFinite(recall.minScore())
                || recall.minScore() < 0d || recall.minScore() > 1d) {
            throw new IllegalStateException("sjherp.memory.recall.min-score 必须是 0 到 1 的有限数");
        }
        if (recall.maxContextChars() < 1000 || recall.maxContextChars() > 20000) {
            throw new IllegalStateException(
                    "sjherp.memory.recall.max-context-chars 必须在 1000 到 20000 之间");
        }
    }

    private static void requireHttpUri(URI uri, String fieldName) {
        if (uri == null || uri.getScheme() == null
                || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException(fieldName + " 必须是 http/https 地址");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + " 不能为空");
        }
    }

    public record Embedding(String provider, URI baseUrl, String model,
                            int dimension, long timeoutSeconds) {
    }

    public record Vector(String provider, URI baseUrl, String collection,
                         String distance) {
    }

    public record Indexing(long retryDelaySeconds, int batchSize, int maxRetries) {
    }

    public record Recall(int candidateLimit, int maxResults,
                         double minScore, int maxContextChars) {
    }
}
