package com.sjherp.app.config;

import java.time.Duration;
import java.time.Clock;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.memory.MemoryProperties;
import com.sjherp.app.memory.MemoryContextProvider;
import com.sjherp.app.memory.MemoryGovernanceService;
import com.sjherp.app.memory.MemoryIndexingService;
import com.sjherp.app.memory.MemoryIndexStateService;
import com.sjherp.app.memory.MemoryPromptFormatter;
import com.sjherp.app.memory.MemoryRecallService;
import com.sjherp.app.memory.MemoryService;
import com.sjherp.app.memory.SemanticMemoryContextProvider;
import com.sjherp.app.memory.MemoryWriteChannel;
import com.sjherp.app.memory.WriteMemoryTool;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.VectorCollectionSpec;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.domain.gap.GapRecordService;
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
    MemoryIndexStateService memoryIndexStateService(MemoryEntryRepository repository) {
        return new MemoryIndexStateService(repository);
    }

    @Bean
    MemoryIndexingService memoryIndexingService(
            MemoryEntryRepository repository, EmbeddingClient embeddingClient,
            VectorIndex vectorIndex, MemoryIndexStateService stateService,
            MemoryProperties properties) {
        return new MemoryIndexingService(repository, embeddingClient, vectorIndex,
                stateService, properties);
    }

    @Bean
    MemoryRecallService memoryRecallService(EmbeddingClient embeddingClient,
            VectorIndex vectorIndex, MemoryEntryRepository repository,
            MemoryProperties properties) {
        return new MemoryRecallService(embeddingClient, vectorIndex, repository,
                properties.recall(), Clock.systemUTC());
    }

    @Bean
    MemoryPromptFormatter memoryPromptFormatter(MemoryProperties properties) {
        return new MemoryPromptFormatter(properties.recall().maxContextChars());
    }

    @Bean
    MemoryContextProvider memoryContextProvider(MemoryRecallService recallService,
            MemoryPromptFormatter formatter) {
        return new SemanticMemoryContextProvider(recallService, formatter);
    }

    @Bean
    MemoryService memoryService(MemoryEntryRepository repository,
                                DocumentNumberGenerator numberGenerator,
                                ApplicationEventPublisher events) {
        return new MemoryService(repository, numberGenerator, events);
    }

    @Bean
    MemoryGovernanceService memoryGovernanceService(MemoryEntryRepository repository) {
        return new MemoryGovernanceService(repository);
    }

    @Bean
    MemoryWriteChannel memoryWriteChannel(MemoryService memoryService) {
        return new MemoryWriteChannel(memoryService);
    }

    @Bean
    WriteMemoryTool writeMemoryTool(ToolRegistry registry, MemoryWriteChannel channel,
                                    GapRecordService gapRecordService) {
        WriteMemoryTool tool = new WriteMemoryTool(channel, gapRecordService);
        registry.register(tool);
        return tool;
    }

    @Bean
    SmartInitializingSingleton memoryCollectionValidator(
            VectorIndex index, MemoryProperties properties) {
        return () -> index.ensureCollection(new VectorCollectionSpec(
                properties.vector().collection(), properties.embedding().dimension(),
                properties.vector().distance()));
    }
}
