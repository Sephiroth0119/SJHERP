package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.collection.CollectionReceiptRepository;
import com.sjherp.domain.collection.CollectionReceiptService;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.infra.persistence.collection.JdbcCollectionReceiptRepository;

/**
 * 收款单（collection_receipt）装配（M4-T04b，路线图 §6）：仓储 MySQL 实现 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link PurchaseInfraConfig} / {@link SettlementInfraConfig}）。
 *
 * <p><b>事务与审计的装配关系</b>：
 * <ul>
 *   <li>外层事务由 app 的 {@code CollectionReceiptAppService}（@Transactional 写方法）提供——
 *       过账时「单据状态机 + 逐行核销应收 + 现金侧凭证」包成同一原子事务（设计真源 §2.3）；</li>
 *   <li>领域服务 {@link CollectionReceiptService} 不加事务（保持可独立测试），其 @Audited 写方法
 *       由 AuditAspect 自动代理（注册为 Bean 即被代理）；状态流转经注入的
 *       {@link DomainEventPublisher} 自动落审计；</li>
 *   <li>跨聚合协作 Bean（{@code PaymentAccountService}/{@code ReceivableService}/
 *       {@code SettlementService}/{@code AutoVoucherService}/{@code DocumentNumberGenerator}）
 *       由各自既有 Config 装配，{@code CollectionReceiptAppService}（@Service 组件扫描）按类型注入。</li>
 * </ul>
 *
 * <p>Agent 工具（create_collection / approve_collection / post_collection / query_collections）
 * 注册见 {@code DomainToolConfig}（T04c）。
 */
@Configuration
public class CollectionInfraConfig {

    /** 收款单仓储（MySQL 实现，header+line 两表）。 */
    @Bean
    public CollectionReceiptRepository collectionReceiptRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCollectionReceiptRepository(jdbcTemplate);
    }

    /** 收款单领域服务（收款单写操作的唯一入口；@Audited 由 AuditAspect 代理）。 */
    @Bean
    public CollectionReceiptService collectionReceiptService(
            CollectionReceiptRepository collectionReceiptRepository,
            DomainEventPublisher domainEventPublisher) {
        return new CollectionReceiptService(collectionReceiptRepository, domainEventPublisher);
    }
}
