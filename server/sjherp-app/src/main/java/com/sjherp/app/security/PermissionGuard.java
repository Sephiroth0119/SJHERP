package com.sjherp.app.security;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.sjherp.domain.identity.Role;
import com.sjherp.domain.identity.RolePermissions;

/**
 * REST 层权限点判定 bean（M2-T06）：供 {@code @PreAuthorize("@perm.has('partner:write')")} 使用。
 *
 * <p>角色来源是 SecurityContext 中的 ROLE_ 前缀权限（JWT 过滤器逐请求从库刷新写入），
 * 权限点判定走 {@link RolePermissions} 静态映射——与 Agent 工具层
 * （{@link RolePermissionToolChecker}）共用同一张矩阵，保证双层口径一致。
 *
 * <p>判定失败由 Spring Security 统一回 403 {"error":"无权限执行该操作"}
 * （SecurityConfig 的 accessDeniedHandler）。
 */
@Component("perm")
public class PermissionGuard {

    /** 当前登录用户是否被授予指定权限点（未认证/未知 code 一律 false） */
    public boolean has(String permissionCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return RolePermissions.isGrantedCode(rolesOf(authentication), permissionCode);
    }

    /** ROLE_ 前缀权限 → 领域角色集合（无法识别的权限名直接忽略，不参与判定） */
    private static Set<Role> rolesOf(Authentication authentication) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                try {
                    roles.add(Role.valueOf(name.substring("ROLE_".length())));
                } catch (IllegalArgumentException ignored) {
                    // 非领域角色（理论上不存在），忽略
                }
            }
        }
        return roles;
    }
}
