package com.sjherp.domain.identity;

/**
 * 密码哈希端口（领域层接口，infra 用 spring-security-crypto 的 BCrypt 实现）。
 *
 * <p>领域层只见明文（入参）与不透明哈希（出参/比对），不依赖任何加密库；
 * 哈希算法可替换但全局必须统一。
 */
public interface PasswordHasher {

    /** 对明文密码做单向哈希（含盐） */
    String hash(String rawPassword);

    /** 比对明文与已存哈希是否匹配 */
    boolean matches(String rawPassword, String passwordHash);
}
