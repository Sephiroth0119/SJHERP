package com.sjherp.agent.loop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.sjherp.agent.llm.LlmMessage;
import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;

/**
 * 一次执行循环的输入（M1-T02）。
 *
 * @param systemPrompt  系统提示（可为 null：不注入 system 消息）
 * @param history       会话历史消息（含当前用户输入，已转为 LLM 消息形式）
 * @param tools         本次可用工具列表；空列表 = 行为退化为单轮对话
 * @param context       工具执行上下文（审计：谁、哪个会话、什么指令）；tools 非空时必填
 * @param maxIterations 最大 LLM 迭代次数（防失控，默认 {@link #DEFAULT_MAX_ITERATIONS}）；
 *                      用尽后强制做一次不带工具的终轮调用收尾
 * @param timeout       整体超时预算（覆盖循环内全部 LLM 调用与工具执行）；null = 不限
 * @param finalJsonMode 终轮 JSON 输出约束模式（见 {@link FinalJsonMode}）
 */
public record AgentLoopRequest(String systemPrompt, List<LlmMessage> history, List<Tool> tools,
                               ToolContext context, int maxIterations, Duration timeout,
                               FinalJsonMode finalJsonMode) {

    /** 默认最大迭代次数 */
    public static final int DEFAULT_MAX_ITERATIONS = 8;

    public AgentLoopRequest {
        history = history == null ? List.of() : List.copyOf(history);
        tools = tools == null ? List.of() : List.copyOf(tools);
        finalJsonMode = finalJsonMode == null ? FinalJsonMode.NONE : finalJsonMode;
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations 必须 >= 1（实际 " + maxIterations + "）");
        }
        if (!tools.isEmpty()) {
            Objects.requireNonNull(context, "tools 非空时必须提供 ToolContext（审计要求）");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 构造器（零依赖纯 Java，手写 builder） */
    public static final class Builder {

        private String systemPrompt;
        private final List<LlmMessage> history = new ArrayList<>();
        private final List<Tool> tools = new ArrayList<>();
        private ToolContext context;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private Duration timeout;
        private FinalJsonMode finalJsonMode = FinalJsonMode.NONE;

        private Builder() {
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder history(List<LlmMessage> history) {
            this.history.clear();
            if (history != null) {
                this.history.addAll(history);
            }
            return this;
        }

        public Builder tools(List<Tool> tools) {
            this.tools.clear();
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        public Builder context(ToolContext context) {
            this.context = context;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder finalJsonMode(FinalJsonMode finalJsonMode) {
            this.finalJsonMode = finalJsonMode;
            return this;
        }

        public AgentLoopRequest build() {
            return new AgentLoopRequest(systemPrompt, history, tools, context,
                    maxIterations, timeout, finalJsonMode);
        }
    }
}
