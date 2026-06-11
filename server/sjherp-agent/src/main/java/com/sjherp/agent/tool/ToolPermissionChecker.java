package com.sjherp.agent.tool;

/**
 * 工具权限校验接口（M1-T03 安全壳，本期只留接口）。
 *
 * <p>Agent 执行循环在执行声明了 {@link Tool#requiredPermission()} 的工具前调用本接口；
 * 校验不通过时不执行工具，把"权限不足"作为错误结果回灌给模型。
 *
 * <p>真实实现（基于用户角色与功能权限点，M2-T06）由 app 层提供并注入；
 * 在那之前使用 {@link #allowAll()}（放行一切，仅保证调用链路就位）。
 */
public interface ToolPermissionChecker {

    /**
     * 校验上下文中的操作者是否有权执行该工具。
     *
     * @param tool    待执行的工具（其 {@link Tool#requiredPermission()} 非 null 时才会被调用）
     * @param context 执行上下文（含会话与用户标识）
     * @return true 表示放行
     */
    boolean isAllowed(Tool tool, ToolContext context);

    /** 放行一切的占位实现（M2-T06 接入真实权限前的默认值） */
    static ToolPermissionChecker allowAll() {
        return (tool, context) -> true;
    }
}
