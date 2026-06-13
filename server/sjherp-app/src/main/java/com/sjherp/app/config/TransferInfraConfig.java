package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.transfer.TransactionalInventoryPostingAdapter;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.transfer.InventoryPostingPort;
import com.sjherp.domain.transfer.TransferRepository;
import com.sjherp.domain.transfer.TransferService;
import com.sjherp.infra.persistence.transfer.JdbcTransferRepository;

/**
 * 库存调拨装配（M3-T04）：调拨仓储 MySQL 实现 + 库存过账端口适配 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link StocktakeInfraConfig} / {@link InventoryInfraConfig}）。
 *
 * <p><b>事务与审计的装配关系</b>：
 * <ul>
 *   <li>外层事务由 app 的 {@code TransferAppService}（@Transactional 写方法）提供——把
 *       单据状态变更 + 库存两腿过账包成一个原子事务；</li>
 *   <li>领域 {@link TransferService} 不加事务（保持可独立测试），其 @Audited 写方法由
 *       AuditAspect 自动代理（本服务注册为 Bean 即被代理）；</li>
 *   <li>状态流转经注入的 {@link DomainEventPublisher}（AuditConfig 装配
 *       SyncDomainEventPublisher）自动落 document.status_changed 审计；</li>
 *   <li>库存两腿过账经 {@link InventoryPostingPort} → {@link TransactionalInventoryService}
 *       （REQUIRED 加入外层事务），库存两表唯一写入口不被绕过（CLAUDE.md 原则 1）；
 *       同批 execute 内库存服务用调出腿成本作调入成本，金额守恒（拆解 §1.6.5）。</li>
 * </ul>
 *
 * <p>Agent 工具（create_transfer / query_transfer）注册见 {@code DomainToolConfig}
 * （显式 new 列表模式，注册片段随该类维护）。
 */
@Configuration
public class TransferInfraConfig {

    // ---------------- 仓储（MySQL 实现） ----------------

    @Bean
    public TransferRepository transferRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcTransferRepository(jdbcTemplate);
    }

    // ---------------- 库存过账端口（转调库存唯一写入口的事务包装） ----------------

    @Bean
    public InventoryPostingPort transferInventoryPostingPort(
            TransactionalInventoryService transactionalInventoryService) {
        return new TransactionalInventoryPostingAdapter(transactionalInventoryService);
    }

    // ---------------- 领域服务（所有调拨写操作的唯一入口） ----------------

    @Bean
    public TransferService transferService(TransferRepository transferRepository,
                                           InventoryPostingPort transferInventoryPostingPort,
                                           DomainEventPublisher domainEventPublisher) {
        return new TransferService(transferRepository, transferInventoryPostingPort,
                domainEventPublisher);
    }
}
