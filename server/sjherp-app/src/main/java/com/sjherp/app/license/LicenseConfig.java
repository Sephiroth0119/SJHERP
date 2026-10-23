package com.sjherp.app.license;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(LicenseProperties.class)
public class LicenseConfig {

    /**
     * 用 {@link Bean} 注册，避免 {@code @Component Filter} 被所有 {@code @WebMvcTest} 自动拉起。
     */
    @Bean
    public FilterRegistrationBean<LicenseEnforceFilter> licenseEnforceFilter(LicenseService licenseService) {
        FilterRegistrationBean<LicenseEnforceFilter> registration =
                new FilterRegistrationBean<>(new LicenseEnforceFilter(licenseService));
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.addUrlPatterns("/api/*", "/actuator/*");
        return registration;
    }
}
