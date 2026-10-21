package com.sjherp.domain.identity;

import java.util.Locale;
import java.util.Set;

/**
 * 登录密码强度策略（M8-T03 安全基线）。
 *
 * <p>与 {@code deploy/lib.sh} 的 {@code generate_admin_password} 对齐：安装脚本生成
 * {@code Adm} + 12 位十六进制（含大小写字母与数字，不含特殊字符）。因此本策略
 * <b>不强制特殊字符</b>，避免一键安装生成的初始密码被拒绝。
 *
 * <p>规则：8–64 位；至少各含一个大写字母、小写字母、数字；拒绝常见弱口令；
 * 若提供用户名则不得与用户名相同（忽略大小写）。
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    /** 常见弱口令（小写比较）。不含安装脚本会生成的 Adm+hex 形态。 */
    static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "passw0rd", "passw0rd1",
            "admin123", "admin1234", "admin888", "12345678", "123456789",
            "qwerty123", "qwertyui", "changeme", "changeme1", "letmein",
            "letmein1", "welcome1", "abc12345", "iloveyou1");

    private PasswordPolicy() {
    }

    /** 校验明文密码（创建/改密/重置的唯一规则入口）。 */
    public static void validate(String rawPassword) {
        validate(rawPassword, null);
    }

    /** 校验明文密码，并拒绝与用户名相同。 */
    public static void validate(String rawPassword, String username) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "密码至少 " + MIN_LENGTH + " 位，且须同时包含大写字母、小写字母和数字");
        }
        if (rawPassword.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("密码不能超过 " + MAX_LENGTH + " 位");
        }
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        for (int i = 0; i < rawPassword.length(); i++) {
            char c = rawPassword.charAt(i);
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasUpper || !hasLower || !hasDigit) {
            throw new IllegalArgumentException("密码须同时包含大写字母、小写字母和数字");
        }
        String normalized = rawPassword.toLowerCase(Locale.ROOT);
        if (COMMON_PASSWORDS.contains(normalized)) {
            throw new IllegalArgumentException("密码过于常见，请更换");
        }
        if (username != null && !username.isBlank()
                && normalized.equals(username.strip().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("密码不能与登录名相同");
        }
    }
}
