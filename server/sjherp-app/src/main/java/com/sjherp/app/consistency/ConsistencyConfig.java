package com.sjherp.app.consistency;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.sjherp.domain.consistency.ConsistencyCheckRunRepository;
import com.sjherp.domain.notification.SystemNotificationRepository;
import com.sjherp.infra.persistence.consistency.JdbcConsistencyCheckRunRepository;
import com.sjherp.infra.persistence.notification.JdbcSystemNotificationRepository;

/**
 * 数据一致性校验单元装配（M3-T13 检查 Agent）。
 *
 * <p>本包内 {@link ConsistencyCheckService}（@Service）、{@link ConsistencyCheckDao}（@Repository）、
 * {@link ConsistencyController}（@RestController）、{@link ConsistencyScheduledChecker}（@Component，
 * @ConditionalOnProperty 默认关）均由 {@code @SpringBootApplication} 组件扫描自动装配，本类不显式 new。
 *
 * <p>本类仅承载 {@code @EnableScheduling}：Spring Boot 不默认启用调度，不加此注解 {@link ConsistencyScheduledChecker}
 * 的 {@code @Scheduled} 不生效。落点选本配置类（局部）而非 {@code SjherpApplication}（全局），缩小影响面——
 * 当前全仓仅本单元有定时任务，避免无关任务被意外激活。
 *
 * <p><b>不在此注册 Agent 工具</b>：{@code run_consistency_check} 的 registry.register 由
 * {@code DomainToolConfig}（共享文件，集成阶段统一改）完成，本类不碰 ToolRegistry（CLAUDE.md：
 * 工具注册集中一处）。
 *
 * <p>Explicitly registers the consistency-run and system-notification JDBC adapters because
 * application component scanning is limited to {@code com.sjherp.app}.
 */
@Configuration
@EnableScheduling
public class ConsistencyConfig {

    @Bean
    ConsistencyCheckRunRepository consistencyCheckRunRepository(JdbcTemplate jdbc) {
        return new JdbcConsistencyCheckRunRepository(jdbc);
    }

    @Bean
    SystemNotificationRepository systemNotificationRepository(JdbcTemplate jdbc) {
        return new JdbcSystemNotificationRepository(jdbc);
    }
}
