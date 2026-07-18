package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.agent.tool.ToolRegistry;
import com.sjherp.app.memory.WriteMemoryTool;
import com.sjherp.app.memory.MemoryContextProvider;
import com.sjherp.app.memory.MemoryPromptFormatter;
import com.sjherp.app.memory.MemoryRecallService;
import com.sjherp.domain.gap.GapRecordService;
import com.sjherp.infra.memory.QdrantVectorException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class MemoryInfraConfigTest {

    private HttpServer server;
    private String qdrantUrl;
    private volatile int collectionDimension;

    @BeforeEach
    void startServer() throws IOException {
        collectionDimension = 1024;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/", this::handleCollection);
        server.start();
        qdrantUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 关闭时没有任何记忆基础设施bean() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryInfraConfig.class)
                .withPropertyValues("sjherp.memory.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmbeddingClient.class);
                    assertThat(context).doesNotHaveBean(VectorIndex.class);
                    assertThat(context).doesNotHaveBean(MemoryEntryRepository.class);
                    assertThat(context).doesNotHaveBean(MemoryRecallService.class);
                    assertThat(context).doesNotHaveBean(MemoryPromptFormatter.class);
                    assertThat(context).doesNotHaveBean(MemoryContextProvider.class);
                });
    }

    @Test
    void 开启且collection规格一致时装配本地实现() {
        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingClient.class);
            assertThat(context).hasSingleBean(VectorIndex.class);
            assertThat(context).hasSingleBean(MemoryEntryRepository.class);
            assertThat(context).hasSingleBean(WriteMemoryTool.class);
            assertThat(context).hasSingleBean(MemoryRecallService.class);
            assertThat(context).hasSingleBean(MemoryPromptFormatter.class);
            assertThat(context).hasSingleBean(MemoryContextProvider.class);
            assertThat(context.getBean(ToolRegistry.class).find(WriteMemoryTool.NAME)).isPresent();
        });
    }

    @Test
    void 开启但collection维度不一致时拒绝启动() {
        collectionDimension = 768;

        enabledRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(QdrantVectorException.class);
        });
    }

    private ApplicationContextRunner enabledRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(MemoryInfraConfig.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(DocumentNumberGenerator.class, () -> mock(DocumentNumberGenerator.class))
                .withBean(ApplicationEventPublisher.class, () -> mock(ApplicationEventPublisher.class))
                .withBean(ToolRegistry.class, ToolRegistry::new)
                .withBean(GapRecordService.class, () -> mock(GapRecordService.class))
                .withPropertyValues(
                        "sjherp.memory.enabled=true",
                        "sjherp.memory.embedding.provider=ollama",
                        "sjherp.memory.embedding.base-url=http://127.0.0.1:11434",
                        "sjherp.memory.embedding.model=qwen3-embedding:0.6b",
                        "sjherp.memory.embedding.dimension=1024",
                        "sjherp.memory.embedding.timeout-seconds=60",
                        "sjherp.memory.vector.provider=qdrant",
                        "sjherp.memory.vector.base-url=" + qdrantUrl,
                        "sjherp.memory.vector.collection=sjherp-memory-qwen3-0_6b-1024-v1",
                        "sjherp.memory.vector.distance=COSINE",
                        "sjherp.memory.indexing.retry-delay-seconds=30",
                        "sjherp.memory.indexing.batch-size=50",
                        "sjherp.memory.indexing.max-retries=8");
    }

    private void handleCollection(HttpExchange exchange) throws IOException {
        String body = """
                {"result":{"config":{"params":{"vectors":{"size":%d,"distance":"Cosine"}}}},"status":"ok"}
                """.formatted(collectionDimension);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
