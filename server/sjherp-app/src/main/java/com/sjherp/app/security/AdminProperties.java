package com.sjherp.app.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 初始管理员配置（前缀 sjherp.admin）。
 *
 * <p>password 必须通过环境变量 SJHERP_ADMIN_PASSWORD 配置，不提供默认值。
 * 首次启动时若数据库中无用户，则使用该密码创建 admin 账户。
 *
 * @param password 初始管理员密码（环境变量 SJHERP_ADMIN_PASSWORD）
 */
@ConfigurationProperties(prefix = "sjherp.admin")
public record AdminProperties(String password) {

    /**
     * 是否已配置初始管理员密码。
     */
    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }
}
