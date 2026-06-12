package com.sjherp.app.security;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sjherp.agent.tool.Tool;
import com.sjherp.agent.tool.ToolContext;
import com.sjherp.agent.tool.ToolPermissionChecker;
import com.sjherp.domain.identity.RolePermissions;
import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;

/**
 * 工具权限校验的真实实现（M2-T06，替换框架占位 {@code ToolPermissionChecker.allowAll()}）。
 *
 * <p>校验链：{@code ToolContext.userId}（会话所属 sys_user.id 的字符串形式）
 * → 查 {@link UserRepository} 拿当前角色（逐次实时查库，与 JWT 过滤器同口径：
 * 改角色/停用立即生效，不信任任何快照）→ {@link RolePermissions} 静态映射判定。
 *
 * <p>失败语义（宁拒勿放）：userId 缺失/非法、用户不存在、用户已停用、
 * 权限点未知、查库异常——一律拒绝。拒绝后由 AgentLoop 把"权限不足"错误
 * 回灌给模型，模型向用户礼貌说明，不中断对话。
 *
 * <p>注：本检查器只在工具声明了 {@code requiredPermission()} 时被 AgentLoop 调用，
 * 查询类工具（无权限点）不产生额外查库开销。
 */
public final class RolePermissionToolChecker implements ToolPermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionToolChecker.class);

    private final UserRepository userRepository;

    public RolePermissionToolChecker(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository 不能为空");
    }

    @Override
    public boolean isAllowed(Tool tool, ToolContext context) {
        String required = tool.requiredPermission();
        if (required == null) {
            // 无权限要求的工具登录即可（AgentLoop 实际不会对这类工具调用本方法，此处兜底）
            return true;
        }
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            log.warn("工具权限校验拒绝：上下文缺少 userId（tool={}, permission={}）", tool.name(), required);
            return false;
        }
        long userId;
        try {
            userId = Long.parseLong(context.userId().strip());
        } catch (NumberFormatException e) {
            log.warn("工具权限校验拒绝：userId 不是合法用户主键（tool={}, userId={}）", tool.name(), context.userId());
            return false;
        }
        try {
            return userRepository.findById(userId)
                    .filter(User::isEnabled)
                    .map(user -> RolePermissions.isGrantedCode(user.getRoles(), required))
                    .orElseGet(() -> {
                        log.warn("工具权限校验拒绝：用户不存在或已停用（tool={}, userId={}）", tool.name(), userId);
                        return false;
                    });
        } catch (RuntimeException e) {
            // 查库异常按无权限处理（宁拒勿放）；错误回灌后模型会向用户说明
            log.error("工具权限校验异常，按拒绝处理（tool={}, userId={}）", tool.name(), userId, e);
            return false;
        }
    }
}
