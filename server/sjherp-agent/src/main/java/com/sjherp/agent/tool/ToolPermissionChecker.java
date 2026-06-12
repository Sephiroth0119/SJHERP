package com.sjherp.agent.tool;

/**
 * 工具权限校验接口（M1-T03 安全壳）。
 *
 * <p>Agent 执行循环在执行声明了 {@link Tool#requiredPermission()} 的工具前调用本接口；
 * 校验不通过时不执行工具，把"权限不足"作为错误结果回灌给模型。
 *
 * <p>真实实现（M2-T06 起）由 app 层提供并注入：RolePermissionToolChecker
 * 按 ToolContext.userId 查用户角色 → RolePermissions 静态矩阵判定。
 * {@link #allowAll()} 仅保留给单元测试与无权限场景。
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

    /** 放行一切的占位实现（仅供单元测试使用；运行时装配真实实现，见 AgentInfraConfig） */
    static ToolPermissionChecker allowAll() {
        return (tool, context) -> true;
    }
}
