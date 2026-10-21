package com.sjherp.app.security;

import java.util.Arrays;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产 compose 默认 forbid-dev-profile=true：禁止带着 local/dev 把 OpenAPI 放到公网。
 * 本地测试不设置该属性，保持可开 swagger。
 */
@Component
@ConditionalOnProperty(name = "sjherp.security.forbid-dev-profile", havingValue = "true")
public class DevProfileGuard {

    private final Environment environment;

    public DevProfileGuard(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rejectDevProfileInProduction() {
        boolean dev = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "dev".equals(p) || "local".equals(p));
        if (dev) {
            throw new IllegalStateException(
                    "生产禁止激活 local/dev profile（会开放 /swagger-ui 与 /v3/api-docs）。"
                            + "请去掉 SPRING_PROFILES_ACTIVE，或设置 SJHERP_FORBID_DEV_PROFILE=false 仅用于排障。");
        }
    }
}
