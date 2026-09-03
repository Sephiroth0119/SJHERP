package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;

import com.sjherp.app.notification.InAppNotificationChannel;
import com.sjherp.app.notification.NotificationService;

class Task4SpringConstructorResolutionTest {

    @Test
    void productionConstructorsAreExplicitlyResolvableForSpring() {
        for (Class<?> serviceType : List.of(ConsistencyCheckRunner.class,
                InAppNotificationChannel.class, NotificationService.class)) {
            Constructor<?> constructor = BeanUtils.getResolvableConstructor(serviceType);

            assertThat(constructor.isAnnotationPresent(Autowired.class))
                    .as("%s production constructor is explicitly injectable",
                            serviceType.getSimpleName())
                    .isTrue();
        }
    }
}
