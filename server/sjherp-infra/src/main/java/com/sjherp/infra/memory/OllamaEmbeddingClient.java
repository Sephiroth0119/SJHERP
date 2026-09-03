package com.sjherp.infra.memory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sjherp.domain.memory.EmbeddingClient;
import com.sjherp.domain.memory.EmbeddingPurpose;
import com.sjherp.domain.memory.EmbeddingVector;

/** 使用 Ollama 原生 {@code /api/embed} 的本地嵌入客户端。 */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    private static final int ERROR_BODY_LIMIT = 500;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final URI endpoint;
    private final String model;
    private final int expectedDimension;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaEmbeddingClient(URI baseUri, String model, int expectedDimension, Duration timeout) {
        String normalizedBaseUri = normalizeBaseUri(baseUri);
        this.endpoint = URI.create(normalizedBaseUri + "/api/embed");
        this.model = requireText(model, "嵌入模型");
        if (expectedDimension < 1) {
            throw new IllegalArgumentException("期望向量维度必须为正数");
        }
        this.expectedDimension = expectedDimension;
        this.timeout = requirePositive(timeout, "请求超时");
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public EmbeddingVector embed(String text, EmbeddingPurpose purpose) {
        String checkedText = requireText(text, "嵌入文本");
        Objects.requireNonNull(purpose, "嵌入用途不能为空");

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", checkedText);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OllamaEmbeddingException("Ollama embed HTTP " + response.statusCode()
                        + ": " + responseSnippet(response.body(), checkedText));
            }
            return parseVector(response.body());
        } catch (HttpTimeoutException ex) {
            throw new OllamaEmbeddingException("Ollama embed 请求超时", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OllamaEmbeddingException("Ollama embed 请求被中断", ex);
        } catch (IOException ex) {
            throw new OllamaEmbeddingException("Ollama embed 网络请求失败", ex);
        }
    }

    private EmbeddingVector parseVector(String responseBody) {
        try {
            JsonNode embeddings = mapper.readTree(responseBody).path("embeddings");
            if (!embeddings.isArray() || embeddings.isEmpty()) {
                throw new OllamaEmbeddingException("Ollama embed 返回空向量数组");
            }
            JsonNode first = embeddings.get(0);
            if (first == null || !first.isArray()) {
                throw new OllamaEmbeddingException("Ollama embed 第一条向量格式非法");
            }
            if (first.size() != expectedDimension) {
                throw new OllamaEmbeddingException("Ollama embed 返回向量维度不一致：期望 "
                        + expectedDimension + "，实际 " + first.size());
            }

            List<Float> values = new ArrayList<>(expectedDimension);
            for (JsonNode value : first) {
                if (!value.isNumber()) {
                    throw new OllamaEmbeddingException("Ollama embed 向量含非数值元素");
                }
                double doubleValue = value.doubleValue();
                float floatValue = (float) doubleValue;
                if (!Double.isFinite(doubleValue) || !Float.isFinite(floatValue)) {
                    throw new OllamaEmbeddingException("Ollama embed 向量含非有限数");
                }
                values.add(floatValue);
            }
            return new EmbeddingVector(model, expectedDimension, values);
        } catch (JsonProcessingException ex) {
            throw new OllamaEmbeddingException("Ollama embed 响应 JSON 解析失败", ex);
        } catch (IllegalArgumentException ex) {
            throw new OllamaEmbeddingException("Ollama embed 返回向量非法", ex);
        }
    }

    private static String responseSnippet(String responseBody, String requestText) {
        String safe = responseBody == null ? "" : responseBody.replace(requestText, "[REDACTED]");
        return safe.length() <= ERROR_BODY_LIMIT ? safe : safe.substring(0, ERROR_BODY_LIMIT);
    }

    private static String normalizeBaseUri(URI baseUri) {
        Objects.requireNonNull(baseUri, "Ollama baseUrl 不能为空");
        String value = baseUri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Ollama baseUrl 不能为空");
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.strip();
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + "必须为正数");
        }
        return value;
    }
}
