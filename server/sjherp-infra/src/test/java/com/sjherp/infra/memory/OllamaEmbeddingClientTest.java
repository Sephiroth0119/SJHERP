package com.sjherp.infra.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sjherp.domain.memory.EmbeddingPurpose;
import com.sjherp.domain.memory.EmbeddingVector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class OllamaEmbeddingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicReference<Response> response = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", this::handle);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void 调用原生embed并返回严格1024维() {
        respond(200, jsonWithVector(1024));
        OllamaEmbeddingClient client = client(Duration.ofSeconds(2));

        EmbeddingVector vector = client.embed("大客户口径", EmbeddingPurpose.DOCUMENT);

        assertThat(vector.model()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(vector.dimension()).isEqualTo(1024);
        assertThat(vector.values()).hasSize(1024);
        assertThat(requestBody.get()).contains("qwen3-embedding:0.6b", "大客户口径");
    }

    @Test
    void 返回维度不符时拒绝() {
        respond(200, jsonWithVector(3));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2))
                .embed("文本", EmbeddingPurpose.DOCUMENT))
                .isInstanceOf(OllamaEmbeddingException.class)
                .hasMessageContaining("期望 1024");
    }

    @Test
    void 空向量响应被拒绝() {
        respond(200, "{\"embeddings\":[]}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2))
                .embed("文本", EmbeddingPurpose.QUERY))
                .isInstanceOf(OllamaEmbeddingException.class)
                .hasMessageContaining("空");
    }

    @Test
    void 非成功响应截断且不泄露请求原文() {
        String secret = "这段原文不得进入异常";
        respond(500, secret + "-" + "x".repeat(800));

        assertThatThrownBy(() -> client(Duration.ofSeconds(2))
                .embed(secret, EmbeddingPurpose.DOCUMENT))
                .isInstanceOf(OllamaEmbeddingException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining(secret)
                .satisfies(error -> assertThat(error.getMessage()).hasSizeLessThan(650));
    }

    @Test
    void 整体请求超时转为领域可识别异常() {
        response.set(new Response(200, jsonWithVector(1024), 300));

        assertThatThrownBy(() -> client(Duration.ofMillis(50))
                .embed("文本", EmbeddingPurpose.DOCUMENT))
                .isInstanceOf(OllamaEmbeddingException.class)
                .hasMessageContaining("超时");
    }

    private OllamaEmbeddingClient client(Duration timeout) {
        return new OllamaEmbeddingClient(baseUri, "qwen3-embedding:0.6b", 1024, timeout);
    }

    private void respond(int status, String body) {
        response.set(new Response(status, body, 0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Response configured = response.get();
        if (configured.delayMillis() > 0) {
            try {
                Thread.sleep(configured.delayMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = configured.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(configured.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String jsonWithVector(int dimension) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode vector = MAPPER.createArrayNode();
        for (int i = 0; i < dimension; i++) {
            vector.add((i + 1.0) / dimension);
        }
        root.putArray("embeddings").add(vector);
        return root.toString();
    }

    private record Response(int status, String body, long delayMillis) {
    }
}
