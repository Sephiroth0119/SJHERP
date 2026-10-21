package com.sjherp.infra.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AesGcmSecretEncryptorTest {

    @Test
    void 往返解密得到原文_密文不含明文() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        AesGcmSecretEncryptor encryptor = new AesGcmSecretEncryptor(key);
        String secret = "sk-live-example-key";
        String cipher = encryptor.encrypt(secret);
        assertThat(cipher).doesNotContain(secret);
        assertThat(encryptor.decrypt(cipher)).isEqualTo(secret);
        assertThat(encryptor.encrypt(secret)).isNotEqualTo(cipher);
    }

    @Test
    void 错误密钥无法解密() {
        byte[] keyA = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] keyB = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
        String cipher = new AesGcmSecretEncryptor(keyA).encrypt("sk-test");
        assertThatThrownBy(() -> new AesGcmSecretEncryptor(keyB).decrypt(cipher))
                .isInstanceOf(IllegalStateException.class);
    }
}
