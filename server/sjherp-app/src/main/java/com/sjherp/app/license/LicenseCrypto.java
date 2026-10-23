package com.sjherp.app.license;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 签发/验签。验签公钥可随应用分发；签发私钥只放在离线签发端，生产私钥不入库。
 */
public final class LicenseCrypto {

    private LicenseCrypto() {
    }

    public static String sign(LicenseDocument document, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(document.canonicalBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new LicenseFormatException("Ed25519 签发失败", ex);
        }
    }

    public static boolean verify(LicenseDocument document, PublicKey publicKey) {
        if (document.sig() == null) {
            return false;
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getUrlDecoder().decode(padBase64Url(document.sig()));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(document.canonicalBytes());
            return signature.verify(signatureBytes);
        } catch (GeneralSecurityException | RuntimeException ex) {
            return false;
        }
    }

    public static PublicKey publicKeyFromPem(String pem) {
        try {
            byte[] der = decodePem(pem, "PUBLIC KEY");
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | RuntimeException ex) {
            throw new LicenseFormatException("无法解析 Ed25519 公钥 PEM", ex);
        }
    }

    public static PrivateKey privateKeyFromPem(String pem) {
        try {
            byte[] der = decodePem(pem, "PRIVATE KEY");
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | RuntimeException ex) {
            throw new LicenseFormatException("无法解析 Ed25519 私钥 PEM", ex);
        }
    }

    static byte[] decodePem(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new LicenseFormatException("PEM 为空");
        }
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < 0 || stop <= start) {
            throw new LicenseFormatException("PEM 缺少 " + type + " 块");
        }
        String body = pem.substring(start + begin.length(), stop).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String padBase64Url(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "====".substring(remainder);
    }
}
