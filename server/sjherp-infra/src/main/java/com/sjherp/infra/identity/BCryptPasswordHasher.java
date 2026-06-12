package com.sjherp.infra.identity;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.sjherp.domain.identity.PasswordHasher;

/**
 * BCrypt 密码哈希实现（spring-security-crypto，仅 crypto 单模块）。
 *
 * <p>默认强度（cost=10）对小企业场景足够；V6 迁移中 admin 种子密码
 * 的哈希与此实现兼容（同为 $2a$ 前缀 BCrypt）。
 */
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
