package com.sjherp.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API 限流配置（M8-T03，前缀 {@code sjherp.security.rate-limit}）。
 *
 * <p>默认打开；测试可用 {@code sjherp.security.rate-limit.enabled=false} 关闭。
 * 仅覆盖登录与聊天发消息等高滥用入口，避免误伤工作台列表刷新。
 */
@ConfigurationProperties(prefix = "sjherp.security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int loginPerMinute,
        @DefaultValue("60") int chatPerMinute,
        @DefaultValue("true") boolean trustForwardedHeaders) {
}
