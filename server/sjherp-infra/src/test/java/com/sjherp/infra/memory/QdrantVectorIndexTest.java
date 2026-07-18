package com.sjherp.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.VectorCollectionSpec;
import com.sjherp.domain.memory.VectorMatch;
import com.sjherp.domain.memory.VectorPoint;
import com.sjherp.domain.memory.VectorQuery;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class QdrantVectorIndexTest {

    private static final VectorCollectionSpec SPEC_1024 =
            new VectorCollectionSpec("memory-v1", 1024, "COSINE");

    private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
    private final List<Request> requests = new ArrayList<>();
    private HttpServer server;
    private QdrantVectorIndex client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        client = new QdrantVectorIndex(baseUri, "memory-v1", Duration.ofSeconds(2));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void collection不存在时创建1024维Cosine集合() {
        enqueue(404, "{\"status\":{\"error\":\"not found\"}}");
        enqueue(200, "{\"result\":true,\"status\":\"ok\"}");

        client.ensureCollection(SPEC_1024);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(1).method()).isEqualTo("PUT");
        assertThat(requests.get(1).body())
                .contains("\"size\":1024", "\"distance\":\"Cosine\"");
    }

    @Test
    void 已有集合维度不一致时拒绝() {
        enqueue(200, collectionInfo(768, "Cosine"));

        assertThatThrownBy(() -> client.ensureCollection(SPEC_1024))
                .isInstanceOf(QdrantVectorException.class)
                .hasMessageContaining("维度不一致");
    }

    @Test
    void 已有集合距离不一致时拒绝() {
        enqueue(200, collectionInfo(1024, "Dot"));

        assertThatThrownBy(() -> client.ensureCollection(SPEC_1024))
                .isInstanceOf(QdrantVectorException.class)
                .hasMessageContaining("距离不一致");
    }

    @Test
    void upsert的payload严格使用白名单且不含原文() {
        enqueue(200, "{\"result\":{\"status\":\"completed\"},\"status\":\"ok\"}");

        client.upsert(point());

        Request request = requests.get(0);
        assertThat(request.path()).isEqualTo("/collections/memory-v1/points?wait=true");
        assertThat(request.body())
                .contains("memory_entry_id", "tenant_id", "memory_type", "memory_status", "source_type")
                .doesNotContain("title", "content", "大客户口径");
    }

    @Test
    void 删除只按真源主键移除派生点() {
        enqueue(200, "{\"result\":{\"status\":\"completed\"},\"status\":\"ok\"}");

        client.delete(17L);

        assertThat(requests.get(0).path())
                .isEqualTo("/collections/memory-v1/points/delete?wait=true");
        assertThat(requests.get(0).body()).contains("\"points\":[17]");
    }

    @Test
    void 查询携带租户活动状态和类型过滤并解析命中() {
        enqueue(200, """
                {"result":{"points":[{"id":17,"score":0.91},{"id":18,"score":0.82}]},"status":"ok"}
                """);
        VectorQuery query = new VectorQuery(List.of(0.1f, 0.2f), 0L,
                Set.of(MemoryType.BUSINESS_TERM), 5, 0.8);

        List<VectorMatch> matches = client.search(query);

        assertThat(matches).extracting(VectorMatch::memoryEntryId).containsExactly(17L, 18L);
        assertThat(requests.get(0).body())
                .contains("tenant_id", "memory_status", "ACTIVE", "memory_type", "BUSINESS_TERM",
                        "\"limit\":5", "\"score_threshold\":0.8")
                .contains("\"with_payload\":false");
    }

    @Test
    void 非成功响应转为有界异常() {
        enqueue(503, "x".repeat(900));

        assertThatThrownBy(() -> client.upsert(point()))
                .isInstanceOf(QdrantVectorException.class)
                .hasMessageContaining("HTTP 503")
                .satisfies(error -> assertThat(error.getMessage()).hasSizeLessThan(600));
    }

    private static VectorPoint point() {
        return new VectorPoint(17L, 0L, MemoryType.BUSINESS_TERM,
                MemoryStatus.ACTIVE, MemorySourceType.USER_INPUT, List.of(0.1f, 0.2f));
    }

    private void enqueue(int status, String body) {
        responses.add(new Response(status, body));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        synchronized (requests) {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body));
        }
        Response response = responses.remove();
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String collectionInfo(int dimension, String distance) {
        return """
                {"result":{"config":{"params":{"vectors":{"size":%d,"distance":"%s"}}}},"status":"ok"}
                """.formatted(dimension, distance);
    }

    private record Response(int status, String body) {
    }

    private record Request(String method, String path, String body) {
    }
}
