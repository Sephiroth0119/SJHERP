package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.payment.PaymentDisbursementRepository;
import com.sjherp.domain.payment.PaymentDisbursementService;
import com.sjherp.infra.persistence.payment.JdbcPaymentDisbursementRepository;

/**
 * 付款单（payment_disbursement）装配（M4-T04b，路线图 §6）：仓储 MySQL 实现 + 领域服务。
 *
 * <p>与收款单 {@link CollectionInfraConfig} 对称。domain/infra 的类不加 Spring 注解（保持可独立测试），
 * 统一在此显式装配（约定同 {@link PurchaseInfraConfig} / {@link SettlementInfraConfig}）。
 *
 * <p><b>事务与审计的装配关系</b>：
 * <ul>
 *   <li>外层事务由 app 的 {@code PaymentDisbursementAppService}（@Transactional 写方法）提供——
 *       过账时「单据状态机 + 逐行核销应付 + 现金侧凭证」包成同一原子事务（设计真源 §2.3）；</li>
 *   <li>领域服务 {@link PaymentDisbursementService} 不加事务（保持可独立测试），其 @Audited 写方法
 *       由 AuditAspect 自动代理（注册为 Bean 即被代理）；状态流转经注入的
 *       {@link DomainEventPublisher} 自动落审计；</li>
 *   <li>跨聚合协作 Bean（{@code PaymentAccountService}/{@code AccountsPayableRepository}/
 *       {@code SettlementService}/{@code AutoVoucherService}/{@code DocumentNumberGenerator}）
 *       由各自既有 Config 装配，{@code PaymentDisbursementAppService}（@Service 组件扫描）按类型注入。</li>
 * </ul>
 *
 * <p>Agent 工具（create_payment / approve_payment / post_payment / query_payments）
 * 注册见 {@code DomainToolConfig}（T04c）。
 */
@Configuration
public class PaymentInfraConfig {

    /** 付款单仓储（MySQL 实现，header+line 两表）。 */
    @Bean
    public PaymentDisbursementRepository paymentDisbursementRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPaymentDisbursementRepository(jdbcTemplate);
    }

    /** 付款单领域服务（付款单写操作的唯一入口；@Audited 由 AuditAspect 代理）。 */
    @Bean
    public PaymentDisbursementService paymentDisbursementService(
            PaymentDisbursementRepository paymentDisbursementRepository,
            DomainEventPublisher domainEventPublisher) {
        return new PaymentDisbursementService(paymentDisbursementRepository, domainEventPublisher);
    }
}
