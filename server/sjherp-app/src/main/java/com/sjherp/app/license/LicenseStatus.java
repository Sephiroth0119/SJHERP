package com.sjherp.app.license;

/**
 * 离线许可运行时状态（M8-T08）。未配置不挡业务；硬阻断仅 {@code SJHERP_LICENSE_ENFORCE=true}
 * 且状态为过期/签名无效/无法解析时生效。
 */
public enum LicenseStatus {
    UNCONFIGURED("未配置"),
    VALID("有效"),
    GRACE("宽限期内"),
    EXPIRED("已过期"),
    INVALID_SIGNATURE("签名无效"),
    MALFORMED("无法解析");

    private final String label;

    LicenseStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
