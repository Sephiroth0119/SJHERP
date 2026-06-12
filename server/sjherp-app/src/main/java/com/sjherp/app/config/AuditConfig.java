package com.sjherp.app.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.audit.AuditAspect;
import com.sjherp.app.audit.AuditDomainEventListener;
import com.sjherp.app.audit.AuditMetrics;
import com.sjherp.app.audit.TransactionAwareAuditWriter;
import com.sjherp.app.event.SyncDomainEventPublisher;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.infra.persistence.audit.AuditLogRepository;
import com.sjherp.infra.persistence.audit.JdbcAuditLogRepository;

/**
 * 统一审计装配（M2-T07，CLAUDE.md 原则 3）：
 * <ul>
 *   <li>审计仓储（V9 迁移 audit_log 表）+ 失败计数器；</li>
 *   <li>审计切面：拦截领域 Service Bean 的 @Audited 写方法
 *       （spring-boot-starter-aop 自动开启 AspectJ 自动代理，本切面注册为 Bean 即生效）；</li>
 *   <li>领域事件发布器接线（还 M2-T01 待办）：同步分发，审计监听器订阅
 *       DocumentStatusChangedEvent（为 M3 单据做准备）。</li>
 * </ul>
 * domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配。
 */
@Configuration
public class AuditConfig {

    /** 审计日志仓储（MySQL 实现，只插入与查询） */
    @Bean
    public AuditLogRepository auditLogRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAuditLogRepository(jdbcTemplate);
    }

    /** 审计失败计数器（审计失败不阻塞业务，但必须可发现） */
    @Bean
    public AuditMetrics auditMetrics() {
        return new AuditMetrics();
    }

    /**
     * 事务感知的审计写入器（D-8 幽灵审计修复）：有活动业务事务时 afterCommit 后插
     * （回滚不写），无事务时立即插。切面与事件监听器统一经它落库。
     */
    @Bean
    public TransactionAwareAuditWriter transactionAwareAuditWriter(AuditLogRepository auditLogRepository,
                                                                   AuditMetrics auditMetrics) {
        return new TransactionAwareAuditWriter(auditLogRepository, auditMetrics);
    }

    /** 统一审计切面：每笔业务写操作（@Audited 标注）必有审计记录 */
    @Bean
    public AuditAspect auditAspect(TransactionAwareAuditWriter transactionAwareAuditWriter,
                                   AuditMetrics auditMetrics) {
        return new AuditAspect(transactionAwareAuditWriter, auditMetrics);
    }

    /** 领域事件 → 审计日志监听器（document.status_changed） */
    @Bean
    public AuditDomainEventListener auditDomainEventListener(TransactionAwareAuditWriter transactionAwareAuditWriter,
                                                             AuditMetrics auditMetrics) {
        return new AuditDomainEventListener(transactionAwareAuditWriter, auditMetrics);
    }

    /**
     * 领域事件发布器（M2-T01 留的端口在此真实接线）：同步分发到注册监听器。
     * M3 单据领域服务创建 BusinessDocument 后注入本 Bean，状态流转事件即自动落审计。
     */
    @Bean
    public DomainEventPublisher domainEventPublisher(AuditDomainEventListener auditDomainEventListener) {
        return new SyncDomainEventPublisher(List.of(auditDomainEventListener));
    }
}
