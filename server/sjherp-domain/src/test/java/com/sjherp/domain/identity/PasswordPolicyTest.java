package com.sjherp.domain.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * M8-T03 密码策略：与 install.sh 生成规则兼容，拒绝弱口令。
 */
class PasswordPolicyTest {

    @Test
    void 安装脚本形态_Adm加十六进制放行() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Adm" + "a1b2c3d4e5f6"));
    }

    @Test
    void 同时含大小写与数字放行() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Passw0rd2026"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("Test@Pass123"));
    }

    @Test
    void 不足8位被拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate("Ab1cdef"));
        assertTrue(e.getMessage().contains("至少"));
    }

    @Test
    void 缺少大写或小写或数字被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("password1"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("PASSWORD1"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("Password"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("abcdefgh"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("12345678"));
    }

    @Test
    void 常见弱口令被拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate("Admin123"));
        assertTrue(e.getMessage().contains("常见"));
    }

    @Test
    void 与用户名相同被拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PasswordPolicy.validate("ZhangSan1", "zhangsan1"));
        assertTrue(e.getMessage().contains("登录名"));
    }

    @Test
    void 超长被拒绝() {
        String tooLong = "Aa1" + "x".repeat(62);
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate(tooLong));
    }

    @Test
    void 空密码被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate(null));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate(""));
    }
}
