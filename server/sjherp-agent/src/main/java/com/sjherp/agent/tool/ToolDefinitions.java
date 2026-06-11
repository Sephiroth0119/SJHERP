package com.sjherp.agent.tool;

import java.util.Collection;
import java.util.List;

import com.sjherp.agent.llm.ToolDefinition;

/**
 * {@link Tool} → {@link ToolDefinition} 转换工厂。
 *
 * <p>放在 tool 包（依赖方向 tool → llm，无循环）：llm 包的 ToolDefinition
 * 保持为独立值对象，不感知工具的执行实现；需要把已注册工具暴露给模型时，
 * 由调用方（Agent 执行循环 / app 层）通过本工厂转换。
 */
public final class ToolDefinitions {

    private ToolDefinitions() {
    }

    /** 提取单个工具的模型可见定义（name / description / 参数 JSON Schema） */
    public static ToolDefinition from(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool 不能为空");
        }
        return new ToolDefinition(tool.name(), tool.description(), tool.parameterSchema());
    }

    /** 批量转换（保持迭代顺序） */
    public static List<ToolDefinition> fromAll(Collection<? extends Tool> tools) {
        if (tools == null) {
            return List.of();
        }
        return tools.stream().map(ToolDefinitions::from).toList();
    }
}
