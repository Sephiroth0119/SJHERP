package com.sjherp.app.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录与聊天发消息限流（M8-T03）。按客户端 IP 计数；nginx 反代后信任
 * {@code X-Forwarded-For} / {@code X-Real-IP}。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String REJECT_BODY = "{\"error\":\"请求过于频繁，请稍后再试\"}";

    private static final Pattern CHAT_MESSAGE_PATH =
            Pattern.compile("^/api/chat/sessions/[^/]+/messages$");

    private final RateLimitProperties properties;
    private final SlidingWindowRateLimiter limiter;

    public RateLimitFilter(RateLimitProperties properties) {
        this(properties, new SlidingWindowRateLimiter());
    }

    RateLimitFilter(RateLimitProperties properties, SlidingWindowRateLimiter limiter) {
        this.properties = properties;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        Bucket bucket = bucketFor(request);
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = bucket.prefix() + ":" + clientIp(request);
        if (!limiter.tryAcquire(key, bucket.limit(), Duration.ofMinutes(1))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Retry-After", "60");
            response.getWriter().write(REJECT_BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private Bucket bucketFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = normalizedPath(request);
        if ("/api/auth/login".equals(path) || "/api/auth/password".equals(path)) {
            return new Bucket("login", properties.loginPerMinute());
        }
        if (CHAT_MESSAGE_PATH.matcher(path).matches()) {
            return new Bucket("chat", properties.chatPerMinute());
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        if (properties.trustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].strip();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.strip();
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
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
        return uri.toLowerCase(Locale.ROOT);
    }

    private record Bucket(String prefix, int limit) {
    }
}
