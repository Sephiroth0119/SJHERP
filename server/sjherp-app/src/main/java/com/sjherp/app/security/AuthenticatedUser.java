package com.sjherp.app.security;

import java.util.Set;

import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.User;

/**
 * 认证主体（JWT 过滤器放入 SecurityContext 的 principal）。
 *
 * <p>角色与启停状态在过滤器中逐请求从数据库刷新（不信任 token 内快照），
 * 保证停用/改角色立即生效。
 *
 * @param userId      用户主键（sys_user.id；会话 user_id 的落库值）
 * @param username    登录名（审计 created_by/updated_by 记录该值）
 * @param displayName 显示名
 * @param roles       当前角色集合
 */
public record AuthenticatedUser(long userId, String username, String displayName, Set<Role> roles) {

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getRoles());
    }
}
