package com.sjherp.app.pilot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 种子启动开关（M8-T07）。默认关闭，避免生产一键安装自动灌种子账。
 *
 * @param enabled 是否在启动时自动装载（环境变量 {@code SJHERP_DEMO_SEED}）
 * @param pack    种子包 id：sunshine / qinghe
 */
@ConfigurationProperties(prefix = "sjherp.demo-seed")
public record DemoSeedProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("sunshine") String pack) {
}
