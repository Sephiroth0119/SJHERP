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
     * 同步对话补全。
     *
     * @param messages 完整上下文消息列表（含 system 提示与历史）
     * @return 模型回复
     */
    LlmResponse chat(List<LlmMessage> messages);
}
