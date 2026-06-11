package com.sjherp.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sjherp.agent.llm.ToolDefinition;

/**
 * ToolDefinitions 工厂单元测试：Tool → ToolDefinition 的提取。
 */
class ToolDefinitionsTest {

    /** 测试用假工具 */
    private static Tool fakeTool(String name, String description, String schema) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String parameterSchema() {
                return schema;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
                throw new UnsupportedOperationException("测试桩不执行");
            }
        };
    }

    @Test
    void fromShouldExtractNameDescriptionAndSchema() {
        String schema = "{\"type\":\"object\",\"properties\":{\"product_name\":{\"type\":\"string\"}}}";
        ToolDefinition definition = ToolDefinitions.from(fakeTool("get_inventory", "查询库存", schema));

        assertEquals("get_inventory", definition.name());
        assertEquals("查询库存", definition.description());
        assertEquals(schema, definition.parametersJsonSchema());
    }

    @Test
    void fromAllShouldKeepOrderAndHandleNull() {
        List<ToolDefinition> definitions = ToolDefinitions.fromAll(List.of(
                fakeTool("a_tool", "甲", "{}"),
                fakeTool("b_tool", "乙", "{}")));
        assertEquals(List.of("a_tool", "b_tool"), definitions.stream().map(ToolDefinition::name).toList());
        assertTrue(ToolDefinitions.fromAll(null).isEmpty());
    }

    @Test
    void fromNullShouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> ToolDefinitions.from(null));
    }
}
