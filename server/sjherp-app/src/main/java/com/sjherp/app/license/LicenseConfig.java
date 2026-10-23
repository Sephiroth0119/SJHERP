package com.sjherp.app.license;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LicenseProperties.class)
public class LicenseConfig {
}
