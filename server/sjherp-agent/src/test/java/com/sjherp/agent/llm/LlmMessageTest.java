package com.sjherp.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.session.MessageRole;

/**
 * LlmMessage 单元测试：工具调用往返的消息表达与角色约束。
 */
class LlmMessageTest {

    @Test
    void plainFactoriesShouldKeepBackwardCompatibleShape() {
        LlmMessage user = LlmMessage.user("查库存");
        assertEquals(MessageRole.USER, user.role());
        assertEquals("查库存", user.content());
        assertTrue(user.toolCalls().isEmpty());
        assertNull(user.toolCallId());
    }

    @Test
    void assistantWithToolCallsShouldCarryThemAndAllowNullContent() {
        ToolCall call = new ToolCall("call_1", "get_inventory", "{\"product_name\":\"不锈钢板\"}");
        LlmMessage message = LlmMessage.assistant(null, List.of(call));

        assertEquals(MessageRole.ASSISTANT, message.role());
        assertNull(message.content());
        assertTrue(message.hasToolCalls());
        assertEquals("get_inventory", message.toolCalls().get(0).name());
    }

    @Test
    void toolMessageShouldRequireToolCallId() {
        LlmMessage message = LlmMessage.tool("call_1", "{\"qty\":\"1250\"}");
        assertEquals(MessageRole.TOOL, message.role());
        assertEquals("call_1", message.toolCallId());

        assertThrows(IllegalArgumentException.class, () -> LlmMessage.tool(null, "结果"));
        assertThrows(IllegalArgumentException.class, () -> LlmMessage.tool(" ", "结果"));
    }

    @Test
    void toolCallsOnNonAssistantAndToolCallIdOnNonToolShouldBeRejected() {
        ToolCall call = new ToolCall("call_1", "get_inventory", "{}");
        // 非 ASSISTANT 角色不允许携带 toolCalls
        assertThrows(IllegalArgumentException.class,
                () -> new LlmMessage(MessageRole.USER, "x", List.of(call), null));
        // 非 TOOL 角色不允许携带 toolCallId
        assertThrows(IllegalArgumentException.class,
                () -> new LlmMessage(MessageRole.ASSISTANT, "x", List.of(), "call_1"));
    }

    @Test
    void toolCallShouldRequireName() {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("call_1", " ", "{}"));
    }
}
