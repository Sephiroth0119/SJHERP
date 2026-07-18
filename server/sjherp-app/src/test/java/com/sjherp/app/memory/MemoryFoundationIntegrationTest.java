package com.sjherp.app.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.EmbeddingVector;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryCommand;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemoryIndexStatus;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.VectorCollectionSpec;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.domain.memory.VectorMatch;
import com.sjherp.domain.memory.VectorPoint;
import com.sjherp.domain.memory.VectorQuery;
import com.sjherp.infra.memory.QdrantVectorException;
import com.sjherp.infra.memory.QdrantVectorIndex;
import com.sjherp.infra.persistence.memory.JdbcMemoryEntryRepository;

/** MySQL 真源与真实 Qdrant 派生索引的完整恢复、重建验收。 */
@Tag("integration-db")
class MemoryFoundationIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    private static final GenericContainer<?> QDRANT =
            new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.13.4"))
                    .withExposedPorts(6333);
    private static final String COLLECTION = "sjherp-memory-qwen3-0_6b-1024-v1";
    private static final String MODEL = "qwen3-embedding:0.6b";
    private static final Instant NOW = Instant.parse("2026-07-18T06:00:00Z");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static MemoryService memoryService;
    private static MemoryIndexingService indexingService;
    private static MemoryEntryRepository repository;
    private static VectorIndex vectorIndex;
    private static MemoryIndexStateService stateService;
    private static MemoryProperties properties;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        QDRANT.start();
        DataSource migrationDataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(migrationDataSource)
                .locations("classpath:db/migration").load().migrate();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        memoryService = context.getBean(MemoryService.class);
        indexingService = context.getBean(MemoryIndexingService.class);
        repository = context.getBean(MemoryEntryRepository.class);
        vectorIndex = context.getBean(VectorIndex.class);
        stateService = context.getBean(MemoryIndexStateService.class);
        properties = context.getBean(MemoryProperties.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
        if (QDRANT.isRunning()) {
            QDRANT.stop();
        }
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void resetDerivedAndTruthData() throws Exception {
        jdbc.update("DELETE FROM memory_entry");
        deleteCollection(COLLECTION);
        vectorIndex.ensureCollection(new VectorCollectionSpec(COLLECTION, 1024, "COSINE"));
    }

    @Test
    void truthWrite_indexFailureRecovery_andFullRebuildFormClosedLoop() throws Exception {
        MemoryEntry first = memoryService.create(command("大客户口径", "年采购金额超过50万元"), "user:1");
        assertThat(first.getIndexStatus()).isEqualTo(MemoryIndexStatus.PENDING);
        assertThat(indexingService.indexOne(first.getMemoryNo(), "system:memory-indexer")).isTrue();
        assertThat(memoryService.get(first.getMemoryNo()).getIndexStatus())
                .isEqualTo(MemoryIndexStatus.INDEXED);
        assertPointPayloadHasOnlyWhitelist(first);

        MemoryEntry second = memoryService.create(command("采购准时率", "按承诺日计算。"), "user:1");
        MemoryIndexingService failing = new MemoryIndexingService(repository,
                context.getBean(EmbeddingClient.class), failingVectorIndex(), stateService, properties);
        assertThat(failing.indexOne(second.getMemoryNo(), "system:memory-indexer")).isFalse();
        assertThat(memoryService.get(second.getMemoryNo()).getIndexStatus())
                .isEqualTo(MemoryIndexStatus.FAILED);

        MemoryEntry recovered = indexingService.retryIndex(second.getMemoryNo(), "user:1");
        assertThat(recovered.getIndexStatus()).isEqualTo(MemoryIndexStatus.INDEXED);

        deleteCollection(COLLECTION);
        MemoryIndexingService.RebuildResult rebuilt = indexingService.rebuildIndex("user:1");
        assertThat(rebuilt.succeeded()).isEqualTo(2);
        assertThat(rebuilt.failed()).isZero();
        assertThat(point(first.getId()).path("id").longValue()).isEqualTo(first.getId());
        assertThat(point(second.getId()).path("id").longValue()).isEqualTo(second.getId());
        assertPointPayloadHasOnlyWhitelist(first);
    }

    @Test
    void idempotentWriteReplay_persistsOneTruthRow() {
        String memoryKey = "write:integration-session-term";
        MemoryEntry first = memoryService.createIdempotent(memoryKey,
                command("大客户口径", "{\"threshold\":\"500000\"}"), "agent:1");
        MemoryEntry replay = memoryService.createIdempotent(memoryKey,
                command("大客户口径", "{\"threshold\":\"500000\"}"), "agent:1");

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_entry", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void expiredTruth_isExcludedEvenWhenStalePointStillExists() throws Exception {
        MemoryEntry entry = memoryService.create(command("旧口径", "已经失效的口径"), "user:1");
        assertThat(indexingService.indexOne(entry.getMemoryNo(), "system:memory-indexer")).isTrue();
        assertThat(point(entry.getId()).path("id").longValue()).isEqualTo(entry.getId());

        memoryService.expire(entry.getMemoryNo(), "user:1");

        assertThat(memoryService.get(entry.getMemoryNo()).getStatus()).isEqualTo(MemoryStatus.EXPIRED);
        assertThat(repository.findActiveByMemoryKey(entry.getMemoryKey())).isEmpty();
        assertThat(repository.findActiveAfterId(0, 50)).isEmpty();
        assertThat(point(entry.getId()).path("id").longValue()).isEqualTo(entry.getId());
    }

    @Test
    void existingCollectionWithWrongDimension_isRejectedByRealQdrant() throws Exception {
        String badCollection = COLLECTION + "-bad";
        deleteCollection(badCollection);
        QdrantVectorIndex badIndex = new QdrantVectorIndex(qdrantUri(), badCollection,
                Duration.ofSeconds(10));
        badIndex.ensureCollection(new VectorCollectionSpec(badCollection, 768, "COSINE"));

        assertThatThrownBy(() -> badIndex.ensureCollection(
                new VectorCollectionSpec(badCollection, 1024, "COSINE")))
                .isInstanceOf(QdrantVectorException.class)
                .hasMessageContaining("维度不一致");
    }

    private static MemoryEntryCommand command(String title, String content) {
        return new MemoryEntryCommand(MemoryType.METRIC_DEFINITION, title, content,
                MemorySourceType.USER_INPUT, "integration-test", NOW, null);
    }

    private static VectorIndex failingVectorIndex() {
        return new VectorIndex() {
            @Override
            public void ensureCollection(VectorCollectionSpec spec) {
            }

            @Override
            public void upsert(VectorPoint point) {
                throw new QdrantVectorException("simulated unavailable");
            }

            @Override
            public void delete(long memoryEntryId) {
                throw new QdrantVectorException("simulated unavailable");
            }

            @Override
            public List<VectorMatch> search(VectorQuery query) {
                throw new QdrantVectorException("simulated unavailable");
            }
        };
    }

    private static void assertPointPayloadHasOnlyWhitelist(MemoryEntry entry) throws Exception {
        JsonNode payload = point(entry.getId()).path("payload");
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "memory_entry_id", "tenant_id", "memory_type", "memory_status", "source_type");
        assertThat(payload.toString()).doesNotContain(entry.getTitle(), entry.getContent(), "content", "title");
    }

    private static JsonNode point(long id) throws Exception {
        HttpResponse<String> response = send("GET", "/collections/" + COLLECTION
                + "/points/" + id + "?with_payload=true&with_vector=false", null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("result");
    }

    private static void deleteCollection(String collection) throws Exception {
        HttpResponse<String> response = send("DELETE", "/collections/" + collection, null);
        assertThat(response.statusCode()).isIn(200, 404);
    }

    private static HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(qdrantUri() + path))
                .timeout(Duration.ofSeconds(15));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI qdrantUri() {
        return URI.create("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {

        private static final AtomicLong SEQUENCE = new AtomicLong();

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        MemoryProperties memoryProperties() {
            return new MemoryProperties(true,
                    new MemoryProperties.Embedding("ollama", URI.create("http://localhost:11434"),
                            MODEL, 1024, 60),
                    new MemoryProperties.Vector("qdrant", qdrantUri(), COLLECTION, "COSINE"),
                    new MemoryProperties.Indexing(30, 50, 8));
        }

        @Bean
        MemoryEntryRepository memoryEntryRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcMemoryEntryRepository(jdbcTemplate);
        }

        @Bean
        EmbeddingClient embeddingClient() {
            return (text, purpose) -> new EmbeddingVector(MODEL, 1024,
                    Collections.nCopies(1024, 0.125f));
        }

        @Bean
        VectorIndex vectorIndex() {
            return new QdrantVectorIndex(qdrantUri(), COLLECTION, Duration.ofSeconds(15));
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator() {
            return new DefaultDocumentNumberGenerator(scope -> SEQUENCE.incrementAndGet(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        MemoryIndexStateService memoryIndexStateService(MemoryEntryRepository repository) {
            return new MemoryIndexStateService(repository);
        }

        @Bean
        MemoryIndexingService memoryIndexingService(
                MemoryEntryRepository repository, EmbeddingClient embeddingClient,
                VectorIndex vectorIndex, MemoryIndexStateService state,
                MemoryProperties properties) {
            return new MemoryIndexingService(repository, embeddingClient, vectorIndex, state, properties);
        }

        @Bean
        MemoryService memoryService(MemoryEntryRepository repository,
                                    DocumentNumberGenerator numberGenerator,
                                    ApplicationEventPublisher events) {
            return new MemoryService(repository, numberGenerator, events);
        }
    }
}
