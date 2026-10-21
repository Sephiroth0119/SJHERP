package com.sjherp.app.ops;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 运维监控装配（M8-T04）。调度复用 {@code ConsistencyConfig} 已打开的 {@code @EnableScheduling}。
 */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
public class OpsConfig {
}
