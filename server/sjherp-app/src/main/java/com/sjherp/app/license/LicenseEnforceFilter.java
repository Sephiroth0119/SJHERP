package com.sjherp.app.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 硬阻断过滤器：仅 {@code SJHERP_LICENSE_ENFORCE=true} 且许可过期/签名无效/无法解析时拦截业务 API。
 * 未配置永远放行。探活、登录、许可状态与 {@code /api/auth/me} 始终可访问，避免把试点客户锁死。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LicenseEnforceFilter extends OncePerRequestFilter {

    static final String REJECT_BODY = "{\"error\":\"许可无效或已过期，业务接口已暂停（SJHERP_LICENSE_ENFORCE）\"}";

    private final LicenseService licenseService;

    public LicenseEnforceFilter(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        LicenseEvaluation evaluation = licenseService.current();
        if (evaluation == null || !evaluation.blocking() || isAlwaysAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(REJECT_BODY);
    }

    static boolean isAlwaysAllowed(HttpServletRequest request) {
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
        String path = normalizedPath(request);
        if ("GET".equals(method) && "/api/health".equals(path)) {
            return true;
        }
        if ("GET".equals(method) && path.startsWith("/actuator")) {
            return true;
        }
        if ("POST".equals(method) && "/api/auth/login".equals(path)) {
            return true;
        }
        if ("GET".equals(method) && "/api/auth/me".equals(path)) {
            return true;
        }
        return "GET".equals(method) && "/api/license/status".equals(path);
    }

    private static String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        if (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }
}
