package com.sjherp.app.license;

import java.security.PublicKey;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 纯函数校验：未配置 / 过期 / 宽限 / 坏签名。不含 Spring，便于单测。
 */
public final class LicenseEvaluator {

    private LicenseEvaluator() {
    }

    public static LicenseEvaluation evaluate(
            String json,
            PublicKey publicKey,
            Clock clock,
            int defaultGraceDays,
            boolean enforce) {
        if (json == null || json.isBlank()) {
            return LicenseEvaluation.unconfigured(enforce);
        }
        LicenseDocument document;
        try {
            document = LicenseDocument.parse(json);
        } catch (RuntimeException ex) {
            return problem(LicenseStatus.MALFORMED, enforce, "license 文件无法解析：" + rootMessage(ex));
        }
        if (publicKey == null) {
            return problem(LicenseStatus.MALFORMED, enforce, "未加载验签公钥");
        }
        if (!LicenseCrypto.verify(document, publicKey)) {
            return problem(LicenseStatus.INVALID_SIGNATURE, enforce, "license 签名无效");
        }
        LocalDate today = LocalDate.now(clock);
        int graceDays = document.graceDays() == null ? Math.max(defaultGraceDays, 0) : document.graceDays();
        LocalDate expiresAt = document.expiresAt();
        LocalDate graceUntil = expiresAt == null ? null : expiresAt.plusDays(graceDays);
        LicenseStatus status;
        String message;
        if (expiresAt == null) {
            status = LicenseStatus.VALID;
            message = "许可有效（未设置到期日）";
        } else if (!today.isAfter(expiresAt)) {
            status = LicenseStatus.VALID;
            message = "许可有效，到期日 " + expiresAt;
        } else if (graceUntil != null && !today.isAfter(graceUntil)) {
            status = LicenseStatus.GRACE;
            message = "许可已过到期日 " + expiresAt + "，宽限至 " + graceUntil;
        } else {
            status = LicenseStatus.EXPIRED;
            message = "许可已过期（到期日 " + expiresAt + "，宽限至 " + graceUntil + "）";
        }
        boolean blocking = enforce && isHardFailure(status);
        return new LicenseEvaluation(
                status,
                true,
                enforce,
                blocking,
                document.subject(),
                document.issuedAt(),
                expiresAt,
                graceUntil,
                document.seats(),
                document.modules(),
                document.kid(),
                message);
    }

    static boolean isHardFailure(LicenseStatus status) {
        return status == LicenseStatus.EXPIRED
                || status == LicenseStatus.INVALID_SIGNATURE
                || status == LicenseStatus.MALFORMED;
    }

    public static LicenseEvaluation failed(LicenseStatus status, boolean enforce, String message) {
        return problem(status, enforce, message);
    }

    private static LicenseEvaluation problem(LicenseStatus status, boolean enforce, String message) {
        return new LicenseEvaluation(
                status,
                true,
                enforce,
                enforce && isHardFailure(status),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                message);
    }

    private static String rootMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
