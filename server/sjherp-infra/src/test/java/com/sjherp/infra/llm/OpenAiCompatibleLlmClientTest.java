package com.sjherp.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.llm.LlmRequestOptions;
import com.sjherp.agent.llm.LlmResponse;
import com.sjherp.agent.llm.ToolCall;
import com.sjherp.agent.llm.ToolChoice;
import com.sjherp.agent.llm.ToolDefinition;

/**
 * OpenAiCompatibleLlmClient 单元测试（不发真实请求，M1-T07 由 DeepSeekLlmClientTest 改名）：
 * 请求体 JSON 组装（tools/tool_choice/response_format/temperature/消息回灌序列化）
 * 与响应解析（tool_calls）。
 */
class OpenAiCompatibleLlmClientTest {

    private static final String INVENTORY_SCHEMA = """
            {"type":"object","properties":{"product_name":{"type":"string","description":"商品名称"}},\
            "required":["product_name"]}""";

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenAiCompatibleLlmClient client() {
        return new OpenAiCompatibleLlmClient("deepseek", "test-key", "https://api.deepseek.com",
                "deepseek-chat", 0.7, Duration.ofSeconds(30));
    }

    private JsonNode buildBody(List<LlmMessage> messages, LlmRequestOptions options) throws Exception {
        return mapper.readTree(client().buildRequestBody(messages, options));
    }

    // ---------- 请求体组装 ----------

    @Test
    void requestBodyShouldContainToolsInOpenAiFunctionFormat() throws Exception {
        LlmRequestOptions options = LlmRequestOptions.builder()
                .addTool(new ToolDefinition("get_inventory", "查询库存", INVENTORY_SCHEMA))
                .toolChoice(ToolChoice.auto())
                .build();
        JsonNode body = buildBody(List.of(LlmMessage.user("查一下不锈钢板库存")), options);

        JsonNode tools = body.get("tools");
        assertThat(tools).isNotNull();
        assertThat(tools.size()).isEqualTo(1);
        JsonNode tool = tools.get(0);
        assertThat(tool.get("type").asText()).isEqualTo("function");
        JsonNode function = tool.get("function");
        assertThat(function.get("name").asText()).isEqualTo("get_inventory");
        assertThat(function.get("description").asText()).isEqualTo("查询库存");
        // parameters 必须是 JSON 对象（schema 已解析），不是被转义的字符串
        JsonNode parameters = function.get("parameters");
        assertThat(parameters.isObject()).isTrue();
        assertThat(parameters.get("type").asText()).isEqualTo("object");
        assertThat(parameters.at("/properties/product_name/type").asText()).isEqualTo("string");

        assertThat(body.get("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void toolChoiceVariantsShouldSerializeCorrectly() throws Exception {
        ToolDefinition tool = new ToolDefinition("get_inventory", "查询库存", INVENTORY_SCHEMA);
        List<LlmMessage> messages = List.of(LlmMessage.user("hi"));

        // none：字符串形式
        JsonNode noneBody = buildBody(messages,
                LlmRequestOptions.builder().toolChoice(ToolChoice.none()).addTool(tool).build());
        assertThat(noneBody.get("tool_choice").asText()).isEqualTo("none");

        // 指定工具：对象形式 {"type":"function","function":{"name":...}}
        JsonNode functionBody = buildBody(messages, LlmRequestOptions.builder()
                .addTool(tool).toolChoice(ToolChoice.function("get_inventory")).build());
        JsonNode choice = functionBody.get("tool_choice");
        assertThat(choice.get("type").asText()).isEqualTo("function");
        assertThat(choice.at("/function/name").asText()).isEqualTo("get_inventory");

        // 未指定：不出现 tool_choice 字段
        JsonNode defaultBody = buildBody(messages,
                LlmRequestOptions.builder().addTool(tool).build());
        assertThat(defaultBody.has("tool_choice")).isFalse();
    }

    @Test
    void requestBodyShouldHonorJsonFormatAndTemperatureOverride() throws Exception {
        List<LlmMessage> messages = List.of(LlmMessage.user("hi"));

        // 默认：无 response_format、用实例默认温度 0.7、无 tools
        JsonNode defaults = buildBody(messages, LlmRequestOptions.defaults());
        assertThat(defaults.has("response_format")).isFalse();
        assertThat(defaults.has("tools")).isFalse();
        assertThat(defaults.get("temperature").asDouble()).isEqualTo(0.7);

        // 覆盖：json_object + 按次温度 0.1
        JsonNode overridden = buildBody(messages,
                LlmRequestOptions.builder().jsonResponseFormat(true).temperature(0.1).build());
        assertThat(overridden.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(overridden.get("temperature").asDouble()).isEqualTo(0.1);
    }

    @Test
    void assistantToolCallsAndToolMessagesShouldSerializeForReplay() throws Exception {
        // 模拟一次完整工具往返的回灌：user → assistant(tool_calls) → tool(结果)
        ToolCall call = new ToolCall("call_abc", "get_inventory", "{\"product_name\":\"不锈钢板\"}");
        List<LlmMessage> messages = List.of(
                LlmMessage.user("查一下不锈钢板库存"),
                LlmMessage.assistant(null, List.of(call)),
                LlmMessage.tool("call_abc", "{\"product_name\":\"不锈钢板\",\"qty\":\"1250\"}"));
        JsonNode body = buildBody(messages, LlmRequestOptions.defaults());

        JsonNode messageArray = body.get("messages");
        assertThat(messageArray.size()).isEqualTo(3);

        JsonNode assistant = messageArray.get(1);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        assertThat(assistant.get("content").isNull()).isTrue();
        JsonNode toolCalls = assistant.get("tool_calls");
        assertThat(toolCalls.size()).isEqualTo(1);
        assertThat(toolCalls.get(0).get("id").asText()).isEqualTo("call_abc");
        assertThat(toolCalls.get(0).get("type").asText()).isEqualTo("function");
        assertThat(toolCalls.get(0).at("/function/name").asText()).isEqualTo("get_inventory");
        // OpenAI 兼容格式：arguments 是 JSON 字符串，不是对象
        assertThat(toolCalls.get(0).at("/function/arguments").isTextual()).isTrue();
        assertThat(toolCalls.get(0).at("/function/arguments").asText()).contains("不锈钢板");

        JsonNode toolMessage = messageArray.get(2);
        assertThat(toolMessage.get("role").asText()).isEqualTo("tool");
        assertThat(toolMessage.get("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(toolMessage.get("content").asText()).contains("1250");
    }

    @Test
    void invalidToolSchemaShouldFailFast() {
        LlmRequestOptions options = LlmRequestOptions.builder()
                .addTool(new ToolDefinition("bad_tool", "坏 schema", "{not-json"))
                .build();
        assertThatThrownBy(() -> client().buildRequestBody(List.of(LlmMessage.user("hi")), options))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("bad_tool");
    }

    // ---------- 响应解析 ----------

    @Test
    void parseResponseShouldExtractToolCalls() {
        // 固定 JSON 样本：OpenAI 兼容的 tool_calls 响应（content 为 null）
        String body = """
                {"choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_0_x","type":"function",
                    "function":{"name":"get_inventory","arguments":"{\\"product_name\\": \\"不锈钢板\\"}"}}]},
                  "finish_reason":"tool_calls"}]}""";
        LlmResponse response = client().parseResponse(body);

        assertThat(response.content()).isNull();
        assertThat(response.hasToolCalls()).isTrue();
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_0_x");
        assertThat(call.name()).isEqualTo("get_inventory");
        assertThat(call.argumentsJson()).contains("不锈钢板");
    }

    @Test
    void parseResponseShouldExtractPlainContent() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"库存 1250 张"},"finish_reason":"stop"}]}""";
        LlmResponse response = client().parseResponse(body);
        assertThat(response.content()).isEqualTo("库存 1250 张");
        assertThat(response.hasToolCalls()).isFalse();
    }

    @Test
    void parseResponseWithNeitherContentNorToolCallsShouldThrow() {
        assertThatThrownBy(() -> client().parseResponse("{\"choices\":[{\"message\":{\"content\":null}}]}"))
                .isInstanceOf(LlmClientException.class)
                .hasMessageContaining("既无 content 也无 tool_calls");
    }

    // ---------- usage 与 model 解析（M1-T06 可观测性） ----------

    @Test
    void parseResponseShouldExtractModelAndUsage() {
        // 固定 JSON 样本：DeepSeek 实际响应形态（model + usage 同时存在）
        String body = """
                {"id":"abc","model":"deepseek-chat-v3",
                 "choices":[{"message":{"role":"assistant","content":"库存 1250 张"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":321,"completion_tokens":87,"total_tokens":408}}""";
        LlmResponse response = client().parseResponse(body);

        assertThat(response.model()).isEqualTo("deepseek-chat-v3");
        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().promptTokens()).isEqualTo(321);
        assertThat(response.usage().completionTokens()).isEqualTo(87);
    }

    @Test
    void parseResponseWithoutUsageShouldFallBackGracefully() {
        // usage 缺失：usage 为 null；model 缺失：回退到配置的模型名
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}""";
        LlmResponse response = client().parseResponse(body);

        assertThat(response.usage()).isNull();
        assertThat(response.model()).isEqualTo("deepseek-chat");
    }

    @Test
    void parseResponseWithPartialUsageShouldKeepNullForMissingFields() {
        // usage 字段不完整（只有 prompt_tokens）：缺失项为 null，不抛错
        String body = """
                {"model":"deepseek-chat",
                 "choices":[{"message":{"role":"assistant","content":"ok"}}],
                 "usage":{"prompt_tokens":10}}""";
        LlmResponse response = client().parseResponse(body);

        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().promptTokens()).isEqualTo(10);
        assertThat(response.usage().completionTokens()).isNull();
    }
}
