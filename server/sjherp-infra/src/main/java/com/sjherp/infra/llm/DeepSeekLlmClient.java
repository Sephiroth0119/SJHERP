package com.sjherp.infra.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.session.MessageRole;

/**
 * {@link LlmClient} 的 DeepSeek 实现（LLM 抽象层第一个具体厂商实现）。
 *
 * <p>DeepSeek 提供 OpenAI 兼容 API：POST {baseUrl}/chat/completions，Bearer 鉴权。
 * 刻意只用 JDK 自带 {@link HttpClient} + infra 已有的 Jackson，不引入任何厂商 SDK；
 * sjherp-agent 模块保持零依赖，本类只实现其接口。
 *
 * <p>本类不加 Spring 注解（infra 实现类保持可独立测试），由 app 层显式装配。
 * 错误处理：非 200、超时、网络异常一律抛 {@link LlmClientException}（带上下文），
 * 由上层决定兜底。
 */
public class DeepSeekLlmClient implements LlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /** 建连超时（与整体请求超时分开：建连失败应快速暴露） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 异常消息中响应体摘要的最大长度 */
    private static final int BODY_ABBREVIATE_LENGTH = 500;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final Duration timeout;
    private final boolean jsonResponseFormat;

    /**
     * @param apiKey             DeepSeek API Key（必填）
     * @param baseUrl            API 根地址，默认 https://api.deepseek.com
     * @param model              模型名，默认 deepseek-chat
     * @param temperature        采样温度
     * @param timeout            单次请求整体超时
     * @param jsonResponseFormat 是否启用 response_format=json_object（强制模型输出 JSON 对象）
     */
    public DeepSeekLlmClient(String apiKey, String baseUrl, String model,
                             double temperature, Duration timeout, boolean jsonResponseFormat) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DeepSeek api-key 不能为空（配置 sjherp.llm.api-key）");
        }
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(Objects.requireNonNullElse(baseUrl, "https://api.deepseek.com"));
        this.model = Objects.requireNonNullElse(model, "deepseek-chat");
        this.temperature = temperature;
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        this.jsonResponseFormat = jsonResponseFormat;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(messages), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LlmClientException(
                    "DeepSeek 请求超时（timeout=" + timeout.toSeconds() + "s, model=" + model
                            + ", endpoint=" + endpoint() + "）", e);
        } catch (IOException e) {
            throw new LlmClientException(
                    "DeepSeek 网络异常（model=" + model + ", endpoint=" + endpoint() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("DeepSeek 请求被中断（model=" + model + "）", e);
        }

        if (response.statusCode() != 200) {
            throw new LlmClientException(
                    "DeepSeek 返回非 200（status=" + response.statusCode() + ", model=" + model
                            + ", endpoint=" + endpoint() + "）: " + abbreviate(response.body()));
        }
        return parseResponse(response.body());
    }

    /** 组装 OpenAI 兼容请求体（用 Jackson 树模型，避免手拼 JSON 的转义问题） */
    private String buildRequestBody(List<LlmMessage> messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        if (jsonResponseFormat) {
            body.putObject("response_format").put("type", "json_object");
        }
        ArrayNode messageArray = body.putArray("messages");
        for (LlmMessage message : messages) {
            ObjectNode node = messageArray.addObject();
            node.put("role", toApiRole(message.role()));
            node.put("content", message.content());
        }
        return body.toString();
    }

    /** 解析 OpenAI 兼容响应，取 choices[0].message.content */
    private LlmResponse parseResponse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new LlmClientException("DeepSeek 响应 JSON 解析失败（model=" + model + "）: " + abbreviate(body), e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new LlmClientException(
                    "DeepSeek 响应缺少 choices[0].message.content（model=" + model + "）: " + abbreviate(body));
        }
        // 工具调用（tool_calls）尚未接入，恒返回空列表
        return new LlmResponse(content.asText(), List.of());
    }

    /** 统一角色枚举 → OpenAI 兼容角色字符串 */
    private static String toApiRole(MessageRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            // tool 消息需配套 tool_call_id，工具调用接入前不应出现
            case TOOL -> throw new IllegalArgumentException("TOOL 消息暂不支持（DeepSeek 实现尚未接入工具调用）");
        };
    }

    private String endpoint() {
        return baseUrl + CHAT_COMPLETIONS_PATH;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "(空响应体)";
        }
        return text.length() <= BODY_ABBREVIATE_LENGTH ? text : text.substring(0, BODY_ABBREVIATE_LENGTH) + "...";
    }
}
