package com.sjherp.app.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sjherp.domain.identity.User;
import com.sjherp.domain.identity.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT 认证过滤器：解析 Authorization: Bearer 头，逐请求从数据库刷新用户
 * （启停状态与角色以库为准，token 只证明身份），认证成功写入 SecurityContext。
 *
 * <p>token 缺失/非法/用户已停用时不在本过滤器报错，直接放行——后续授权
 * 规则会拦下并由 {@link SecurityConfig} 的入口点统一回 401 {"error": "未登录或登录已过期"}。
 *
 * <p>knife4j 文档路径（/doc.html、/webjars/**、/v3/api-docs/**、/favicon.ico）：
 * 与 {@link SecurityConfig} 同口径，仅在 dev/local profile 下跳过 token 解析。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    /** 用于判断当前激活的 profile，决定是否跳过 knife4j 路径的 token 解析 */
    private final Environment environment;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   UserRepository userRepository,
                                   Environment environment) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.environment = environment;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            jwtService.parseUserId(header.substring(BEARER_PREFIX.length()).strip())
                    .flatMap(userRepository::findById)
                    .filter(User::isEnabled)
                    .ifPresent(this::authenticate);
        }
        chain.doFilter(request, response);
    }

    /** 写入 SecurityContext：principal 为 AuthenticatedUser，权限为 ROLE_角色名 */
    private void authenticate(User user) {
        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(AuthenticatedUser.from(user), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 登录与健康检查无需解析 token（即便带了也忽略，避免无谓的库查询）；
        // 与 SecurityConfig 白名单同口径限定 method（登录仅 POST），其余 method 仍走 token 解析
        String path = request.getServletPath();
        if (("/api/auth/login".equals(path) && "POST".equalsIgnoreCase(request.getMethod()))
                || ("/api/health".equals(path) && "GET".equalsIgnoreCase(request.getMethod()))) {
            return true;
        }
        // knife4j / springdoc 文档路径：仅 dev/local profile 跳过 token 解析（与 SecurityConfig 同口径）
        if (isDevProfile() && isKnife4jPath(path)) {
            return true;
        }
        return false;
    }

    /** 判断当前是否处于开发/本地 profile */
    private boolean isDevProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equals(p) || "local".equals(p));
    }

    /** 判断路径是否属于 knife4j / springdoc 文档路径 */
    private static boolean isKnife4jPath(String path) {
        return "/doc.html".equals(path)
                || path.startsWith("/webjars/")
                || path.startsWith("/v3/api-docs")
                || "/favicon.ico".equals(path);
    }
}
