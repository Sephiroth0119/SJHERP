package com.sjherp.agent.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表。
 *
 * <p>Agent 可用能力的唯一入口：未注册的能力 Agent 一律不可用。
 * 当 Agent 判断"当前能力做不到"时，应走流程缺口通道（后续实现），
 * 而不是绕过注册表自由发挥。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具。名称重复视为装配错误，直接抛异常暴露问题。
     */
    public void register(Tool tool) {
        Tool existing = tools.putIfAbsent(tool.name(), tool);
        if (existing != null) {
            throw new IllegalStateException("工具名称重复注册: " + tool.name());
        }
    }

    /** 按名称查找工具 */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 全部已注册工具（用于拼装提交给 LLM 的工具清单） */
    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }
}
