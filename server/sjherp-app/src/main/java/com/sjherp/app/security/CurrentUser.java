package com.sjherp.app.security;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.sjherp.domain.identity.Role;

/**
 * 当前登录用户工具类：从 SecurityContext 取认证主体（M2-T05）。
 *
 * <p>仅限受 JWT 过滤器保护的请求线程内调用（Controller / 应用服务）；
 * 未认证时抛 IllegalStateException——受保护端点理论上不会出现该情况，
 * 出现即说明过滤器链配置被破坏，宁可快速失败也不回退匿名身份（审计原则）。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 当前认证主体（未认证抛 IllegalStateException） */
    public static AuthenticatedUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new IllegalStateException("当前请求没有认证用户（过滤器链配置异常或在非请求线程调用）");
    }

    /** 用户主键（sys_user.id）的字符串形式——会话 user_id 的落库值 */
    public static String userId() {
        return String.valueOf(get().userId());
    }

    /** 登录名 */
    public static String username() {
        return get().username();
    }

    /** 角色集合 */
    public static Set<Role> roles() {
        return get().roles();
    }

    /**
     * 审计操作人标识（created_by/updated_by 与 audit_log.operator 的来源）：
     * 人工操作记登录名；Agent 自动操作记 agent:&lt;userId&gt;
     * （见 ArchiveToolSupport.operator，M2-T07 审计切面按该约定区分人工与 Agent）。
     */
    public static String operator() {
        return get().username();
    }
}
