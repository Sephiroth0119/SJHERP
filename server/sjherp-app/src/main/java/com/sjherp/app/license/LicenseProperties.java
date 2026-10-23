package com.sjherp.app.license;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 离线许可配置（M8-T08）。默认不强制阻断；未放置文件视为未配置。
 *
 * @param path              license JSON 路径（环境变量 {@code SJHERP_LICENSE_PATH}）
 * @param publicKey         验签公钥 PEM 正文（{@code SJHERP_LICENSE_PUBLIC_KEY}，可留空）
 * @param publicKeyPath     验签公钥 PEM 路径（{@code SJHERP_LICENSE_PUBLIC_KEY_PATH}）
 * @param enforce           硬阻断开关，默认 false
 * @param defaultGraceDays  文件未写 graceDays 时的宽限天数
 * @param refreshMs         定期重读间隔
 */
@ConfigurationProperties(prefix = "sjherp.license")
public record LicenseProperties(
        String path,
        String publicKey,
        String publicKeyPath,
        @DefaultValue("false") boolean enforce,
        @DefaultValue("14") int defaultGraceDays,
        @DefaultValue("3600000") long refreshMs) {
}
