package com.sjherp.app.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.sjherp.domain.identity.UserRepository;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 安全配置（M2-T05）：JWT 无状态认证。
 *
 * <ul>
 *   <li>白名单：POST /api/auth/login（登录入口）、GET /api/health（探活）；</li>
 *   <li>其余 /api/** 一律要求 Bearer token，未认证统一 401 {"error": "未登录或登录已过期"}；</li>
 *   <li>角色/权限点不足（如非 ADMIN 调用用户管理 API、无写权限调用档案写接口
 *       ——M2-T06 权限矩阵见 docs/权限矩阵.md）统一 403 {"error": "无权限执行该操作"}；</li>
 *   <li>无会话（STATELESS）、关 CSRF（纯 token API，不用 Cookie）；</li>
 *   <li>CORS 沿用 {@code WebCorsConfig} 的 MVC 配置（cors() 默认回退 HandlerMappingIntrospector）；</li>
 *   <li>knife4j 文档路径（/doc.html、/webjars/**、/v3/api-docs/**、/favicon.ico）：
 *       仅在 dev/local profile 下放行，生产姿态保持 401。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityJwtProperties.class)
public class SecurityConfig {

    /** 未认证统一响应体（前端 401 全局拦截依赖该文案，不得擅改） */
    private static final String UNAUTHORIZED_BODY = "{\"error\":\"未登录或登录已过期\"}";

    /** 已认证但角色不足的统一响应体 */
    private static final String FORBIDDEN_BODY = "{\"error\":\"无权限执行该操作\"}";

    /** knife4j / springdoc 文档相关路径（仅 dev/local profile 放行） */
    private static final String[] KNIFE4J_PATHS = {
            "/doc.html", "/webjars/**", "/v3/api-docs/**", "/favicon.ico"
    };

    /** 判断当前是否处于开发/本地 profile（dev 或 local 之一激活即视为开发态） */
    private static boolean isDevProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equals(p) || "local".equals(p));
    }

    @Bean
    public JwtService jwtService(SecurityJwtProperties properties) {
        return new JwtService(properties.jwtSecret(), properties.jwtExpireHours());
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService,
                                                           UserRepository userRepository,
                                                           Environment environment) {
        return new JwtAuthenticationFilter(jwtService, userRepository, environment);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   Environment environment)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // ERROR 转发（Spring Boot /error）放行，避免业务异常被误报成 401
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    // knife4j 文档路径仅 dev/local profile 放行；生产姿态保持受保护
                    if (isDevProfile(environment)) {
                        auth.requestMatchers(KNIFE4J_PATHS).permitAll();
                    }
                    // 登录白名单限定 POST（2026-06-12 交叉校验 P2：其余 method 不放行）
                    auth.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                            .anyRequest().authenticated();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) ->
                                writeJson(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY))
                        .accessDeniedHandler((request, response, e) ->
                                writeJson(response, HttpStatus.FORBIDDEN, FORBIDDEN_BODY)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJson(HttpServletResponse response, HttpStatus status, String body)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
