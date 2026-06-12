package com.sjherp.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT 认证配置（前缀 sjherp.security）。
 *
 * <p>secret 生产环境必须用环境变量 SJHERP_JWT_SECRET 覆盖（HS256 要求
 * ≥ 32 字节）；application.yml 中的默认值仅限本地开发。
 *
 * @param jwtSecret      HS256 签名密钥（≥ 32 字节）
 * @param jwtExpireHours token 有效期（小时），默认 12
 */
@ConfigurationProperties(prefix = "sjherp.security")
public record SecurityJwtProperties(
        String jwtSecret,
        @DefaultValue("12") long jwtExpireHours) {
}
