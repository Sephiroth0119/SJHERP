package com.sjherp.app.config;

import java.time.Duration;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.memory.MemoryProperties;
import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.VectorCollectionSpec;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.infra.memory.OllamaEmbeddingClient;
import com.sjherp.infra.memory.QdrantVectorIndex;
import com.sjherp.infra.persistence.memory.JdbcMemoryEntryRepository;

/**
 * 大记忆本地基础设施装配。
 *
 * <p>默认完全关闭：不创建仓储和客户端，也不连接 Ollama/Qdrant。显式开启后，
 * 在应用启动完成前校验 Qdrant collection，规格不一致直接拒绝启动。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemoryProperties.class)
@ConditionalOnProperty(prefix = "sjherp.memory", name = "enabled", havingValue = "true")
public class MemoryInfraConfig {

    @Bean
    MemoryEntryRepository memoryEntryRepository(JdbcTemplate jdbc) {
        return new JdbcMemoryEntryRepository(jdbc);
    }

    @Bean
    EmbeddingClient embeddingClient(MemoryProperties properties) {
        MemoryProperties.Embedding embedding = properties.embedding();
        return new OllamaEmbeddingClient(embedding.baseUrl(), embedding.model(),
                embedding.dimension(), Duration.ofSeconds(embedding.timeoutSeconds()));
    }

    @Bean
    VectorIndex vectorIndex(MemoryProperties properties) {
        return new QdrantVectorIndex(properties.vector().baseUrl(),
                properties.vector().collection(), Duration.ofSeconds(30));
    }

    @Bean
    SmartInitializingSingleton memoryCollectionValidator(
            VectorIndex index, MemoryProperties properties) {
        return () -> index.ensureCollection(new VectorCollectionSpec(
                properties.vector().collection(), properties.embedding().dimension(),
                properties.vector().distance()));
    }
}
