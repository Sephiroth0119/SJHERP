package com.sjherp.agent.tool;

import java.util.Map;

/**
 * Agent 工具接口。
 *
 * <p>设计约束（CLAUDE.md：工具即领域服务）：Agent 能做的所有业务操作都必须是
 * 实现了本接口并注册到 {@link ToolRegistry} 的领域服务方法，带参数校验与权限。
 * 不存在"裸 SQL"或自由写库的工具——这是数据模型完整性原则在框架层的落点。
 */
public interface Tool {

    /** 工具唯一名称（snake_case，例如 create_purchase_order） */
    String name();

    /** 面向 LLM 的工具描述：何时用、做什么、有何前置条件 */
    String description();

    /**
     * 参数定义，JSON Schema 字符串（draft 2020-12 子集即可）。
     * 提交给 LLM 用于生成工具调用参数，同时作为执行前校验的依据。
     */
    String parameterSchema();

    /**
     * 执行工具。
     *
     * @param arguments LLM 给出的调用参数（已按 parameterSchema 解析为键值对）
     * @param context   执行上下文（会话、操作者，用于权限校验与审计日志）
     * @return 结构化执行结果，框架会将其序列化后写回会话消息历史
     */
    ToolResult execute(Map<String, Object> arguments, ToolContext context);
}
