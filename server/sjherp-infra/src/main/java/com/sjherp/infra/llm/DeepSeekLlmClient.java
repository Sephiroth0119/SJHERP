package com.sjherp.infra.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sjherp.agent.llm.LlmClient;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.ToolCall;
import com.sjherp.agent.llm.ToolChoice;
import com.sjherp.agent.llm.ToolDefinition;
import com.sjherp.agent.session.MessageRole;

/**
 * {@link LlmClient} 的 DeepSeek 实现（LLM 抽象层第一个具体厂商实现）。
 *
 * <p>DeepSeek 提供 OpenAI 兼容 API：POST {baseUrl}/chat/completions，Bearer 鉴权。
 * 刻意只用 JDK 自带 {@link HttpClient} + infra 已有的 Jackson，不引入任何厂商 SDK；
 * sjherp-agent 模块保持零依赖，本类只实现其接口。
 *
 * <p>按次参数（response_format / temperature 覆盖 / tools / tool_choice）经
 * {@link LlmRequestOptions} 透传，OpenAI function calling 格式组装与
 * choices[0].message.tool_calls 解析见 {@link #buildRequestBody} / {@link #parseResponse}
 * （包级可见以便单元测试结构断言）。
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

    /**
     * @param apiKey      DeepSeek API Key（必填）
     * @param baseUrl     API 根地址，默认 https://api.deepseek.com
     * @param model       模型名，默认 deepseek-chat
     * @param temperature 默认采样温度（可被 {@link LlmRequestOptions#temperature()} 按次覆盖）
     * @param timeout     单次请求整体超时
     */
    public DeepSeekLlmClient(String apiKey, String baseUrl, String model,
                             double temperature, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DeepSeek api-key 不能为空（配置 sjherp.llm.api-key）");
        }
        this.apiKey = apiKey;
        this.baseUrl = stripTrailingSlash(Objects.requireNonNullElse(baseUrl, "https://api.deepseek.com"));
        this.model = Objects.requireNonNullElse(model, "deepseek-chat");
        this.temperature = temperature;
        this.timeout = Objects.requireNonNull(timeout, "timeout 不能为空");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        Objects.requireNonNull(options, "options 不能为空（无特殊要求传 LlmRequestOptions.defaults()）");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint()))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(messages, options), StandardCharsets.UTF_8))
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

    /**
     * 组装 OpenAI 兼容请求体（用 Jackson 树模型，避免手拼 JSON 的转义问题）。
     * 包级可见：单元测试直接断言 JSON 结构，不发真实请求。
     */
    String buildRequestBody(List<LlmMessage> messages, LlmRequestOptions options) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // 按次温度覆盖：options 未指定时用实例默认值
        body.put("temperature", options.temperature() != null ? options.temperature() : temperature);
        if (options.jsonResponseFormat()) {
            body.putObject("response_format").put("type", "json_object");
        }

        ArrayNode messageArray = body.putArray("messages");
        for (LlmMessage message : messages) {
            messageArray.add(toMessageNode(message));
        }

        if (options.hasTools()) {
            ArrayNode toolArray = body.putArray("tools");
            for (ToolDefinition tool : options.tools()) {
                ObjectNode toolNode = toolArray.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", parseParameterSchema(tool));
            }
        }
        if (options.toolChoice() != null) {
            body.set("tool_choice", toToolChoiceNode(options.toolChoice()));
        }
        return body.toString();
    }

    /** 统一消息 → OpenAI 兼容消息节点（含 assistant 带 tool_calls 与 role=tool 的回灌序列化） */
    private ObjectNode toMessageNode(LlmMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", toApiRole(message.role()));
        switch (message.role()) {
            case TOOL -> {
                // 工具结果消息：必须携带 tool_call_id 与请求关联（LlmMessage 构造时已校验）
                node.put("tool_call_id", message.toolCallId());
                node.put("content", message.content() == null ? "" : message.content());
            }
            case ASSISTANT -> {
                // 纯工具调用的 assistant 消息 content 可为 null（OpenAI 兼容格式允许）
                if (message.content() == null) {
                    node.putNull("content");
                } else {
                    node.put("content", message.content());
                }
                if (message.hasToolCalls()) {
                    ArrayNode toolCallArray = node.putArray("tool_calls");
                    for (ToolCall toolCall : message.toolCalls()) {
                        ObjectNode toolCallNode = toolCallArray.addObject();
                        toolCallNode.put("id", toolCall.id());
                        toolCallNode.put("type", "function");
                        ObjectNode function = toolCallNode.putObject("function");
                        function.put("name", toolCall.name());
                        function.put("arguments", toolCall.argumentsJson() == null ? "{}" : toolCall.argumentsJson());
                    }
                }
            }
            default -> node.put("content", message.content());
        }
        return node;
    }

    /** 工具的参数 JSON Schema 字符串 → JSON 节点（无效 schema 在请求前快速暴露） */
    private JsonNode parseParameterSchema(ToolDefinition tool) {
        String schema = tool.parametersJsonSchema();
        if (schema == null || schema.isBlank()) {
            // 无参数工具：给空对象 schema（OpenAI 兼容 API 要求 parameters 为合法 schema 对象）
            ObjectNode empty = mapper.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            return empty;
        }
        try {
            return mapper.readTree(schema);
        } catch (JsonProcessingException e) {
            throw new LlmClientException(
                    "工具参数 schema 不是合法 JSON（tool=" + tool.name() + "）: " + abbreviate(schema), e);
        }
    }

    /** 统一 ToolChoice → OpenAI 兼容 tool_choice 节点（auto/none 为字符串，指定工具为对象） */
    private JsonNode toToolChoiceNode(ToolChoice toolChoice) {
        return switch (toolChoice.mode()) {
            case AUTO -> mapper.getNodeFactory().textNode("auto");
            case NONE -> mapper.getNodeFactory().textNode("none");
            case FUNCTION -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "function");
                node.putObject("function").put("name", toolChoice.functionName());
                yield node;
            }
        };
    }

    /**
     * 解析 OpenAI 兼容响应：choices[0].message 的 content 与 tool_calls。
     * 包级可见：单元测试用固定 JSON 样本断言解析结果。
     */
    LlmResponse parseResponse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new LlmClientException("DeepSeek 响应 JSON 解析失败（model=" + model + "）: " + abbreviate(body), e);
        }
        JsonNode message = root.path("choices").path(0).path("message");
        JsonNode contentNode = message.path("content");
        String content = contentNode.isMissingNode() || contentNode.isNull() ? null : contentNode.asText();

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode toolCallNode : toolCallsNode) {
                String id = toolCallNode.path("id").asText(null);
                JsonNode function = toolCallNode.path("function");
                String name = function.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    throw new LlmClientException(
                            "DeepSeek tool_calls 缺少 function.name（model=" + model + "）: " + abbreviate(body));
                }
                // arguments 在 OpenAI 兼容格式中是 JSON 字符串，原样透传，由上层解析校验
                String arguments = function.path("arguments").asText("{}");
                toolCalls.add(new ToolCall(id, name, arguments));
            }
        }

        if (content == null && toolCalls.isEmpty()) {
            throw new LlmClientException(
                    "DeepSeek 响应既无 content 也无 tool_calls（model=" + model + "）: " + abbreviate(body));
        }
        return new LlmResponse(content, toolCalls);
    }

    /** 统一角色枚举 → OpenAI 兼容角色字符串 */
    private static String toApiRole(MessageRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
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
