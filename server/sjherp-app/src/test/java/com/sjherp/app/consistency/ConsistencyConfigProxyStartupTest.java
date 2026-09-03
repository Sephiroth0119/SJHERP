package com.sjherp.app.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;
import com.sjherp.domain.notification.SystemNotificationRepository;

class ConsistencyConfigProxyStartupTest {

    @Test
    void startsBothJdbcAdaptersAsUsableClassBasedTransactionProxies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ClassProxyTestConfig.class);
            context.refresh();

            ConsistencyCheckRunRepository runs = context.getBean(ConsistencyCheckRunRepository.class);
            SystemNotificationRepository notifications = context.getBean(SystemNotificationRepository.class);

            assertThat(AopUtils.isCglibProxy(runs)).isTrue();
            assertThat(AopUtils.isCglibProxy(notifications)).isTrue();
            assertThat(runs.findByRunNo(0, "CHK-missing")).isEmpty();
            assertThat(notifications.countUnread(0, 7)).isZero();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ConsistencyConfig.class)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ClassProxyTestConfig {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
            when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            return transactionManager;
        }
    }
}
