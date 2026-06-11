package com.sjherp.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * LlmRequestOptions 单元测试：默认值、builder、约束校验。
 */
class LlmRequestOptionsTest {

    @Test
    void defaultsShouldHaveNoJsonNoTemperatureNoTools() {
        LlmRequestOptions options = LlmRequestOptions.defaults();
        assertFalse(options.jsonResponseFormat());
        assertNull(options.temperature());
        assertTrue(options.tools().isEmpty());
        assertFalse(options.hasTools());
        assertNull(options.toolChoice());
    }

    @Test
    void builderShouldCarryAllFields() {
        ToolDefinition tool = new ToolDefinition("get_inventory", "查询库存",
                "{\"type\":\"object\",\"properties\":{}}");
        LlmRequestOptions options = LlmRequestOptions.builder()
                .jsonResponseFormat(true)
                .temperature(0.2)
                .addTool(tool)
                .toolChoice(ToolChoice.auto())
                .build();

        assertTrue(options.jsonResponseFormat());
        assertEquals(0.2, options.temperature());
        assertEquals(List.of(tool), options.tools());
        assertTrue(options.hasTools());
        assertEquals(ToolChoice.Mode.AUTO, options.toolChoice().mode());
    }

    @Test
    void toolChoiceWithoutToolsShouldBeRejected() {
        // auto/指定工具 但 tools 为空 → 非法（none 除外：明确禁用工具不需要工具列表）
        assertThrows(IllegalArgumentException.class,
                () -> LlmRequestOptions.builder().toolChoice(ToolChoice.auto()).build());
        assertThrows(IllegalArgumentException.class,
                () -> LlmRequestOptions.builder().toolChoice(ToolChoice.function("get_inventory")).build());
    }

    @Test
    void toolChoiceFunctionShouldRequireName() {
        assertThrows(IllegalArgumentException.class, () -> ToolChoice.function(" "));
        assertThrows(IllegalArgumentException.class, () -> new ToolChoice(ToolChoice.Mode.AUTO, "extra"));
    }

    @Test
    void oldChatMethodShouldDelegateWithDefaultOptions() {
        // 旧接口 chat(messages) 必须委托新重载并传 defaults()，保证现有调用方不受影响
        var captured = new Object() {
            List<LlmMessage> messages;
            LlmRequestOptions options;
        };
        LlmClient client = (messages, options) -> {
            captured.messages = messages;
            captured.options = options;
            return new LlmResponse("ok");
        };

        List<LlmMessage> messages = List.of(LlmMessage.user("你好"));
        LlmResponse response = client.chat(messages);

        assertEquals("ok", response.content());
        assertFalse(response.hasToolCalls());
        assertSame(messages, captured.messages);
        assertEquals(LlmRequestOptions.defaults(), captured.options);
    }
}
