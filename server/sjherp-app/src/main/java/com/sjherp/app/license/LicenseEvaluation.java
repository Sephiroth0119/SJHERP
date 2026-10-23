package com.sjherp.app.license;

import java.time.LocalDate;
import java.util.List;

/**
 * 许可校验快照。{@code blocking} 仅在 enforce 打开且状态不可用时为 true。
 */
public record LicenseEvaluation(
        LicenseStatus status,
        boolean configured,
        boolean enforce,
        boolean blocking,
        String subject,
        LocalDate issuedAt,
        LocalDate expiresAt,
        LocalDate graceUntil,
        Integer seats,
        List<String> modules,
        String kid,
        String message) {

    public static LicenseEvaluation unconfigured(boolean enforce) {
        return new LicenseEvaluation(
                LicenseStatus.UNCONFIGURED,
                false,
                enforce,
                false,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                "未配置 license，私有部署默认不挡业务（开发/试用宽松模式）");
    }

    public LicenseDtos.StatusResponse toResponse() {
        return new LicenseDtos.StatusResponse(
                status.name(),
                status.label(),
                configured,
                enforce,
                blocking,
                subject,
                issuedAt == null ? null : issuedAt.toString(),
                expiresAt == null ? null : expiresAt.toString(),
                graceUntil == null ? null : graceUntil.toString(),
                seats,
                modules == null ? List.of() : modules,
                kid,
                message);
    }
}
