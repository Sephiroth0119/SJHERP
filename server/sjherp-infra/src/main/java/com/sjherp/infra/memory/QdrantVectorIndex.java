package com.sjherp.infra.memory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sjherp.domain.memory.MemoryType;
import com.sjherp.domain.memory.VectorCollectionSpec;
import com.sjherp.domain.memory.VectorIndex;
import com.sjherp.domain.memory.VectorMatch;
import com.sjherp.domain.memory.VectorPoint;
import com.sjherp.domain.memory.VectorQuery;

/**
 * Qdrant REST 向量索引实现。
 *
 * <p>payload 由本类按白名单逐字段构造，只包含定位和过滤所需元数据；标题、
 * 原文、来源正文等业务内容禁止进入 Qdrant。召回结果也只返回 MySQL 真源主键。
 */
public final class QdrantVectorIndex implements VectorIndex {

    private static final int ERROR_BODY_LIMIT = 500;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String collection;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public QdrantVectorIndex(URI baseUri, String collection, Duration timeout) {
        this.baseUrl = normalizeBaseUri(baseUri);
        this.collection = requireText(collection, "Qdrant collection");
        this.timeout = requirePositive(timeout, "Qdrant 请求超时");
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public void ensureCollection(VectorCollectionSpec spec) {
        Objects.requireNonNull(spec, "向量集合规格不能为空");
        String path = collectionPath(spec.name());
        HttpResponse<String> response = send("GET", path, null);
        if (response.statusCode() == 404) {
            createCollection(spec, path);
            return;
        }
        requireSuccess(response, "查询 collection");
        validateCollection(response.body(), spec);
    }

    private void createCollection(VectorCollectionSpec spec, String path) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode vectors = root.putObject("vectors");
        vectors.put("size", spec.dimension());
        vectors.put("distance", qdrantDistance(spec.distance()));
        requireSuccess(send("PUT", path, root.toString()), "创建 collection");
    }

    private void validateCollection(String responseBody, VectorCollectionSpec spec) {
        try {
            JsonNode vectors = mapper.readTree(responseBody)
                    .path("result").path("config").path("params").path("vectors");
            JsonNode size = vectors.path("size");
            JsonNode distance = vectors.path("distance");
            if (!size.canConvertToInt() || !distance.isTextual()) {
                throw new QdrantVectorException("Qdrant collection 规格响应格式非法");
            }
            if (size.intValue() != spec.dimension()) {
                throw new QdrantVectorException("Qdrant collection 维度不一致：期望 "
                        + spec.dimension() + "，实际 " + size.intValue());
            }
            if (!distance.textValue().equalsIgnoreCase(qdrantDistance(spec.distance()))) {
                throw new QdrantVectorException("Qdrant collection 距离不一致：期望 "
                        + qdrantDistance(spec.distance()) + "，实际 " + distance.textValue());
            }
        } catch (JsonProcessingException ex) {
            throw new QdrantVectorException("Qdrant collection 响应 JSON 解析失败", ex);
        }
    }

    @Override
    public void upsert(VectorPoint point) {
        Objects.requireNonNull(point, "向量点不能为空");
        ObjectNode root = mapper.createObjectNode();
        ObjectNode pointNode = root.putArray("points").addObject();
        pointNode.put("id", point.memoryEntryId());
        ArrayNode vector = pointNode.putArray("vector");
        point.vector().forEach(vector::add);

        // 严格白名单：禁止直接序列化 MemoryEntry 或任意 DTO。
        ObjectNode payload = mapper.createObjectNode();
        payload.put("memory_entry_id", point.memoryEntryId());
        payload.put("tenant_id", point.tenantId());
        payload.put("memory_type", point.memoryType().name());
        payload.put("memory_status", point.memoryStatus().name());
        payload.put("source_type", point.sourceType().name());
        pointNode.set("payload", payload);

        requireSuccess(send("PUT", collectionPath(collection) + "/points?wait=true", root.toString()),
                "写入向量点");
    }

    @Override
    public void delete(long memoryEntryId) {
        if (memoryEntryId < 1) {
            throw new IllegalArgumentException("大记忆主键必须为正数");
        }
        ObjectNode root = mapper.createObjectNode();
        root.putArray("points").add(memoryEntryId);
        requireSuccess(send("POST", collectionPath(collection) + "/points/delete?wait=true",
                root.toString()), "删除派生向量点");
    }

    @Override
    public List<VectorMatch> search(VectorQuery query) {
        Objects.requireNonNull(query, "向量查询不能为空");
        ObjectNode root = mapper.createObjectNode();
        ArrayNode queryVector = root.putArray("query");
        query.vector().forEach(queryVector::add);
        root.put("limit", query.limit());
        root.put("with_payload", false);
        if (query.minScore() != null) {
            root.put("score_threshold", query.minScore());
        }

        ArrayNode must = root.putObject("filter").putArray("must");
        addValueMatch(must, "tenant_id", query.tenantId());
        addValueMatch(must, "memory_status", "ACTIVE");
        if (!query.memoryTypes().isEmpty()) {
            ObjectNode condition = must.addObject();
            condition.put("key", "memory_type");
            ArrayNode any = condition.putObject("match").putArray("any");
            query.memoryTypes().stream()
                    .sorted(Comparator.comparing(MemoryType::name))
                    .map(MemoryType::name)
                    .forEach(any::add);
        }

        HttpResponse<String> response = send("POST",
                collectionPath(collection) + "/points/query", root.toString());
        requireSuccess(response, "查询向量点");
        return parseMatches(response.body());
    }

    private List<VectorMatch> parseMatches(String responseBody) {
        try {
            JsonNode points = mapper.readTree(responseBody).path("result").path("points");
            if (!points.isArray()) {
                throw new QdrantVectorException("Qdrant 查询响应 points 格式非法");
            }
            List<VectorMatch> matches = new ArrayList<>(points.size());
            for (JsonNode point : points) {
                JsonNode id = point.path("id");
                JsonNode score = point.path("score");
                if (!id.canConvertToLong() || !score.isNumber() || !Double.isFinite(score.doubleValue())) {
                    throw new QdrantVectorException("Qdrant 查询命中格式非法");
                }
                matches.add(new VectorMatch(id.longValue(), score.doubleValue()));
            }
            return List.copyOf(matches);
        } catch (JsonProcessingException ex) {
            throw new QdrantVectorException("Qdrant 查询响应 JSON 解析失败", ex);
        }
    }

    private static void addValueMatch(ArrayNode must, String key, long value) {
        ObjectNode condition = must.addObject();
        condition.put("key", key);
        condition.putObject("match").put("value", value);
    }

    private static void addValueMatch(ArrayNode must, String key, String value) {
        ObjectNode condition = must.addObject();
        condition.put("key", key);
        condition.putObject("match").put("value", value);
    }

    private HttpResponse<String> send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException ex) {
            throw new QdrantVectorException("Qdrant 请求超时", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QdrantVectorException("Qdrant 请求被中断", ex);
        } catch (IOException ex) {
            throw new QdrantVectorException("Qdrant 网络请求失败", ex);
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String action) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new QdrantVectorException(action + "失败，HTTP " + response.statusCode()
                    + ": " + responseSnippet(response.body()));
        }
    }

    private static String responseSnippet(String body) {
        String safe = body == null ? "" : body;
        return safe.length() <= ERROR_BODY_LIMIT ? safe : safe.substring(0, ERROR_BODY_LIMIT);
    }

    private static String collectionPath(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "/collections/" + encoded;
    }

    private static String qdrantDistance(String distance) {
        if ("COSINE".equalsIgnoreCase(distance)) {
            return "Cosine";
        }
        throw new IllegalArgumentException("当前只支持 COSINE 距离");
    }

    private static String normalizeBaseUri(URI baseUri) {
        Objects.requireNonNull(baseUri, "Qdrant baseUrl 不能为空");
        String value = baseUri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Qdrant baseUrl 不能为空");
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
