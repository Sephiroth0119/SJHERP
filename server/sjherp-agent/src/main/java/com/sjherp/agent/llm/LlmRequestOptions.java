package com.sjherp.agent.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次 LLM 调用的按次参数（厂商无关的统一表示）。
 *
 * <p>客户端实例级配置（api-key、模型名、默认温度、超时）仍在具体实现的
 * 构造参数里；本对象承载"每次调用可能不同"的部分：响应格式、温度覆盖、
 * 工具定义与工具选择策略。
 *
 * @param jsonResponseFormat 是否要求模型输出 JSON 对象（OpenAI 兼容 response_format=json_object）
 * @param temperature        采样温度覆盖；null 表示使用客户端实例的默认值
 * @param tools              提交给模型的工具定义列表；空表示本次不提供工具
 * @param toolChoice         工具选择策略；null 表示不传该参数（厂商默认，通常等价 auto）
 */
public record LlmRequestOptions(
        boolean jsonResponseFormat,
        Double temperature,
        List<ToolDefinition> tools,
        ToolChoice toolChoice) {

    private static final LlmRequestOptions DEFAULTS = builder().build();

    public LlmRequestOptions {
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (toolChoice != null && toolChoice.mode() != ToolChoice.Mode.NONE && tools.isEmpty()) {
            throw new IllegalArgumentException("toolChoice=" + toolChoice.mode() + " 但 tools 为空");
        }
    }

    /** 全默认参数（不强制 JSON、用客户端默认温度、不带工具） */
    public static LlmRequestOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 是否携带工具定义 */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /** 构造器（零依赖纯 Java，手写 builder） */
    public static final class Builder {

        private boolean jsonResponseFormat;
        private Double temperature;
        private final List<ToolDefinition> tools = new ArrayList<>();
        private ToolChoice toolChoice;

        private Builder() {
        }

        public Builder jsonResponseFormat(boolean jsonResponseFormat) {
            this.jsonResponseFormat = jsonResponseFormat;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools.clear();
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        public Builder addTool(ToolDefinition tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public LlmRequestOptions build() {
            return new LlmRequestOptions(jsonResponseFormat, temperature, tools, toolChoice);
        }
    }
}
