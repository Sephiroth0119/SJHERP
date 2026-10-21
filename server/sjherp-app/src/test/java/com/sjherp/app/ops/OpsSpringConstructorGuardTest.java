package com.sjherp.app.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 生产 jar 里 Spring 遇到多个构造器且未标 {@code @Autowired} 会走无参路径，
 * 直接 {@code No default constructor found} 起不来（M8-T04 CI 部署资产已踩过）。
 */
class OpsSpringConstructorGuardTest {

    @Test
    void 运维SpringBean构造器可被注入() {
        List<Class<?>> types = List.of(
                AlertWebhookClient.class,
                LlmDailySummaryService.class,
                LlmDailySummaryJob.class,
                HttpMetricsSummaryService.class,
                OpsController.class,
                OpsExceptionHandler.class);
        for (Class<?> type : types) {
            boolean springBean = type.isAnnotationPresent(Component.class)
                    || type.isAnnotationPresent(Service.class)
                    || type.isAnnotationPresent(RestController.class)
                    || type.isAnnotationPresent(RestControllerAdvice.class);
            assertThat(springBean).as("%s 应是 Spring bean", type.getSimpleName()).isTrue();
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            boolean single = constructors.length == 1;
            boolean marked = Arrays.stream(constructors)
                    .anyMatch(ctor -> ctor.isAnnotationPresent(Autowired.class));
            assertThat(single || marked)
                    .as("%s 有 %d 个构造器，多构造器必须 @Autowired", type.getSimpleName(), constructors.length)
                    .isTrue();
        }
    }
}
