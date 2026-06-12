package com.sjherp.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
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
 * DeepSeek function calling 真实集成测试（M1-T01 验收：真实调用演示一次工具往返）。
 *
 * <p>默认不执行：@Tag("integration") 被父 POM 的 excludedGroups=integration 排除，
 * CI（mvn verify）不会跑到（无 API Key 环境不能挂）。本地手动运行：
 * <pre>mvn test -pl sjherp-infra -Dgroups=integration -DexcludedGroups=none</pre>
 *
 * <p>API Key 从 sjherp-app 的 application-local.yml 读取（不入库、不进代码）；
 * 读不到时跳过（Assumptions），不算失败。遇 429 限流：等 60 秒重试，最多 3 次。
 */
@Tag("integration")
class DeepSeekToolCallingIntegrationTest {

    /** sjherp-app 本地密钥文件（surefire 工作目录为模块根 sjherp-infra） */
    private static final Path LOCAL_YML =
            Path.of("..", "sjherp-app", "src", "main", "resources", "application-local.yml");

    private static final String INVENTORY_SCHEMA = """
            {"type":"object","properties":{"product_name":{"type":"string","description":"商品名称"}},\
            "required":["product_name"]}""";

    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final Duration RATE_LIMIT_WAIT = Duration.ofSeconds(60);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void functionCallingRoundTripAgainstRealDeepSeek() throws Exception {
        String apiKey = readApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "未找到 application-local.yml 中的 api-key（sjherp.llm.providers.deepseek.api-key），跳过集成测试");

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "deepseek", apiKey, "https://api.deepseek.com", "deepseek-chat", 0.7, Duration.ofSeconds(60));

        ToolDefinition inventoryTool = new ToolDefinition(
                "get_inventory", "按商品名称查询当前库存数量", INVENTORY_SCHEMA);
        LlmRequestOptions options = LlmRequestOptions.builder()
                .addTool(inventoryTool)
                .toolChoice(ToolChoice.auto())
                .build();

        // 第一轮：用户提问 → 期望模型发起 get_inventory 工具调用
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system("你是 ERP 库存助手。查询库存时必须调用 get_inventory 工具，不得编造数据。"));
        messages.add(LlmMessage.user("查一下不锈钢板库存"));

        LlmResponse first = chatWithRateLimitRetry(client, messages, options);
        System.out.println("[集成测试] 第一轮响应 content=" + first.content()
                + ", toolCalls=" + first.toolCalls());

        assertThat(first.hasToolCalls())
                .as("模型应发起工具调用")
                .isTrue();
        ToolCall call = first.toolCalls().stream()
                .filter(c -> "get_inventory".equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("toolCalls 中没有 get_inventory：" + first.toolCalls()));
        assertThat(call.id()).isNotBlank();
        JsonNode arguments = mapper.readTree(call.argumentsJson());
        assertThat(arguments.path("product_name").asText())
                .as("arguments 应包含产品名")
                .contains("不锈钢");

        // 第二轮：把工具结果以 TOOL 消息回灌 → 期望模型给出引用结果的最终回答
        messages.add(LlmMessage.assistant(first.content(), first.toolCalls()));
        messages.add(LlmMessage.tool(call.id(),
                "{\"product_name\":\"不锈钢板\",\"quantity\":\"1250\",\"unit\":\"张\",\"warehouse\":\"主仓\"}"));

        LlmResponse second = chatWithRateLimitRetry(client, messages, options);
        System.out.println("[集成测试] 第二轮响应 content=" + second.content()
                + ", toolCalls=" + second.toolCalls());

        assertThat(second.content())
                .as("回灌工具结果后模型应给出引用结果的最终文本回答")
                .isNotBlank()
                .contains("1250");
    }

    /** 429 限流兜底：等 60 秒重试，最多 3 次（与任务说明一致） */
    private LlmResponse chatWithRateLimitRetry(OpenAiCompatibleLlmClient client,
                                               List<LlmMessage> messages,
                                               LlmRequestOptions options) throws InterruptedException {
        LlmClientException last = null;
        for (int attempt = 1; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            try {
                return client.chat(messages, options);
            } catch (LlmClientException e) {
                if (!e.getMessage().contains("status=429")) {
                    throw e;
                }
                last = e;
                System.out.println("[集成测试] 遇 429 限流（第 " + attempt + " 次），等待 60 秒重试");
                Thread.sleep(RATE_LIMIT_WAIT.toMillis());
            }
        }
        throw new IllegalStateException("DeepSeek 持续限流（429），重试 " + MAX_RATE_LIMIT_RETRIES + " 次仍失败", last);
    }

    /** 从 application-local.yml 提取 api-key（简单正则，避免引入 YAML 依赖） */
    private static String readApiKey() throws Exception {
        if (!Files.exists(LOCAL_YML)) {
            return null;
        }
        String yml = Files.readString(LOCAL_YML);
        Matcher matcher = Pattern.compile("api-key:\\s*(\\S+)").matcher(yml);
        return matcher.find() ? matcher.group(1) : null;
    }
}
