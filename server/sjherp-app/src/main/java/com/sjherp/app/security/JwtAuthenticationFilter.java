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
 * JWT 认证过滤器：解析 Authorization 头，逐请求从数据库刷新用户
 * （启停状态与角色以库为准，token 只证明身份），认证成功写入 SecurityContext。
 *
 * <p>Authorization 头格式：兼容标准 {@code Bearer <token>}（前缀大小写不敏感）
 * 与「裸 token」两种写法。前者去掉 "Bearer " 前缀后取其余部分；后者整串即 token。
 * 无论哪种，strip 首尾空白后一律交给 {@link JwtService#parseUserId} 做密码学全量验签
 * （签名/过期校验不放松），验签失败返回 empty 即不认证。
 * <p>容忍裸 token 的原因：knife4j 纯 UI 模式（仅 knife4j-openapi3-ui，无 autoconfigure
 * starter）的全局 Authorize 不会把 token 注入调试请求头；用户需在「请求头部」手填
 * {@code Authorization: <token>}，此时多半不带 "Bearer " 前缀。容忍前缀缺失不削弱安全性
 * （仍全量验签），同时兼容标准 swagger-ui 的 {@code Bearer} 写法。
 *
 * <p>token 缺失/非法/用户已停用时不在本过滤器报错，直接放行——后续授权
 * 规则会拦下并由 {@link SecurityConfig} 的入口点统一回 401 {"error": "未登录或登录已过期"}。
 *
 * <p>API 文档路径（/doc.html、/swagger-ui/**、/swagger-ui.html、/webjars/**、
 * /v3/api-docs/**、/favicon.ico）：与 {@link SecurityConfig} 同口径，仅在 dev/local
 * profile 下跳过 token 解析。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Bearer 前缀（大小写不敏感比较；存在则剥离，不存在则整串当 token） */
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
        String token = extractToken(request.getHeader("Authorization"));
        if (token != null && !token.isEmpty()) {
            jwtService.parseUserId(token)
                    .flatMap(userRepository::findById)
                    .filter(User::isEnabled)
                    .ifPresent(this::authenticate);
        }
        chain.doFilter(request, response);
    }

    /**
     * 从 Authorization 头提取裸 token：兼容 {@code Bearer <token>}（前缀大小写不敏感）
     * 与「裸 token」两种写法，剥离前缀后 strip 首尾空白。头为空返回 null。
     * 提取出的串仍由 {@link JwtService#parseUserId} 做密码学全量验签，本方法不削弱安全性。
     */
    private static String extractToken(String header) {
        if (header == null) {
            return null;
        }
        String value = header.strip();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            value = value.substring(BEARER_PREFIX.length());
        }
        return value.strip();
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

    /** 判断路径是否属于 knife4j / springdoc 文档路径（含标准 swagger-ui） */
    private static boolean isKnife4jPath(String path) {
        return "/doc.html".equals(path)
                || path.startsWith("/swagger-ui")
                || path.startsWith("/webjars/")
                || path.startsWith("/v3/api-docs")
                || "/favicon.ico".equals(path);
    }
}
