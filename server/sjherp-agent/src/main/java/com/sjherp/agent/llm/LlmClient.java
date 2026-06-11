package com.sjherp.agent.llm;

import java.util.List;

/**
 * LLM 抽象层（CLAUDE.md：自建抽象层，可切换）。
 *
 * <p>Agent 框架与业务代码只依赖本接口；DeepSeek / 通义 / Claude / GPT 等
 * 具体厂商实现放在 sjherp-infra，通过配置切换。检查 Agent、开发者 Agent 等
 * 关键环节可配置强模型——即同一接口的不同实例。
 *
 * <p>本模块刻意不包含任何厂商实现与 SDK 依赖。
 */
public interface LlmClient {

    /**
     * 同步对话补全（全默认按次参数）。
     *
     * @param messages 完整上下文消息列表（含 system 提示与历史）
     * @return 模型回复
     */
    default LlmResponse chat(List<LlmMessage> messages) {
        return chat(messages, LlmRequestOptions.defaults());
    }

    /**
     * 同步对话补全，带按次参数（响应格式 / 温度覆盖 / 工具定义 / 工具选择）。
     *
     * @param messages 完整上下文消息列表（含 system 提示、历史、工具结果回灌）
     * @param options  本次调用参数，不可为 null（无特殊要求传 {@link LlmRequestOptions#defaults()}）
     * @return 模型回复（可能包含工具调用请求）
     */
    LlmResponse chat(List<LlmMessage> messages, LlmRequestOptions options);
}
