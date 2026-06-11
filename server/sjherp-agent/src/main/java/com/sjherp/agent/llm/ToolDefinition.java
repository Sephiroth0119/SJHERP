package com.sjherp.agent.llm;

/**
 * 提交给 LLM 的工具定义（厂商无关的统一表示）。
 *
 * <p>刻意定义为独立值对象、不依赖 tool 包：llm 包只描述"模型能看到什么"，
 * 与工具的执行实现解耦。从 {@code Tool} 到本对象的转换由 tool 包的
 * {@code ToolDefinitions} 工厂完成（依赖方向 tool → llm，无循环）。
 *
 * @param name                 工具唯一名称（snake_case）
 * @param description          面向 LLM 的工具描述：何时用、做什么、有何前置条件
 * @param parametersJsonSchema 参数定义，JSON Schema 字符串（draft 2020-12 子集）
 */
public record ToolDefinition(String name, String description, String parametersJsonSchema) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolDefinition.name 不能为空");
        }
    }
}
