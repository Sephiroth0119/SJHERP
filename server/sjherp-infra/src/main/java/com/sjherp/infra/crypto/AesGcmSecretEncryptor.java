package com.sjherp.infra.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 机密字段加密（M8-T03）。当前生产密钥（JWT、DB、LLM API Key）走
 * {@code deploy/.env}，数据库无 API Key 列；本类供日后若把密钥落入 MySQL 时使用，
 * 禁止对普通业务列做全库加密表演。
 */
public final class AesGcmSecretEncryptor {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random;

    public AesGcmSecretEncryptor(byte[] keyBytes) {
        Objects.requireNonNull(keyBytes, "加密密钥不能为空");
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256-GCM 密钥必须是 32 字节");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
        this.random = new SecureRandom();
    }

    public String encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "明文不能为空");
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("机密字段加密失败", e);
        }
    }

    public String decrypt(String payload) {
        Objects.requireNonNull(payload, "密文不能为空");
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文过短");
            }
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("机密字段解密失败", e);
        }
    }
}
