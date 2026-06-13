package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.sales.ReceivablePostingAdapter;
import com.sjherp.app.sales.TransactionalInventoryPostingAdapter;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.receivable.ReceivableRepository;
import com.sjherp.domain.receivable.ReceivableService;
import com.sjherp.domain.sales.InventoryPostingPort;
import com.sjherp.domain.sales.ReceivablePostingPort;
import com.sjherp.domain.sales.SalesDeliveryRepository;
import com.sjherp.domain.sales.SalesDeliveryService;
import com.sjherp.domain.sales.SalesInvoiceRepository;
import com.sjherp.domain.sales.SalesInvoiceService;
import com.sjherp.domain.sales.SalesOrderRepository;
import com.sjherp.domain.sales.SalesOrderService;
import com.sjherp.infra.persistence.receivable.JdbcReceivableRepository;
import com.sjherp.infra.persistence.sales.JdbcSalesDeliveryRepository;
import com.sjherp.infra.persistence.sales.JdbcSalesInvoiceRepository;
import com.sjherp.infra.persistence.sales.JdbcSalesOrderRepository;

/**
 * 销售线装配（M3-T08/T09/T10）：订单/出库/发票仓储 MySQL 实现 + 库存过账端口适配 +
 * 应收挂账端口适配 + 各领域服务（含应收）。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link StocktakeInfraConfig} / {@link TransferInfraConfig}）。
 *
 * <p><b>事务与审计的装配关系</b>：
 * <ul>
 *   <li>外层事务由 app 的各 AppService（@Transactional 写方法）提供——把单据状态变更 +
 *       库存过账 / 应收挂账 / 回写订单包成一个原子事务；</li>
 *   <li>领域服务不加事务（保持可独立测试），其 @Audited 写方法由 AuditAspect 自动代理；</li>
 *   <li>状态流转经注入的 {@link DomainEventPublisher}（AuditConfig 装配 SyncDomainEventPublisher）
 *       自动落 document.status_changed 审计；</li>
 *   <li>销售出库过账经 {@link InventoryPostingPort} → {@link TransactionalInventoryService}
 *       （REQUIRED 加入外层事务），库存唯一写入口不被绕过（CLAUDE.md 原则 1）；出库成本（COGS）
 *       由库存服务算出并回填出库行；</li>
 *   <li>销售发票过账经 {@link ReceivablePostingPort} → {@link ReceivableService}
 *       （同外层事务）生成应收。</li>
 * </ul>
 *
 * <p>Agent 工具（create_sales_order / query_sales_order）注册见 {@code DomainToolConfig}
 * （显式 new 列表模式，注册片段随该类维护）。
 */
@Configuration
public class SalesInfraConfig {

    // ---------------- 仓储（MySQL 实现） ----------------

    @Bean
    public SalesOrderRepository salesOrderRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSalesOrderRepository(jdbcTemplate);
    }

    @Bean
    public SalesDeliveryRepository salesDeliveryRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSalesDeliveryRepository(jdbcTemplate);
    }

    @Bean
    public SalesInvoiceRepository salesInvoiceRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSalesInvoiceRepository(jdbcTemplate);
    }

    @Bean
    public ReceivableRepository receivableRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcReceivableRepository(jdbcTemplate);
    }

    // ---------------- 端口适配（转调库存唯一写入口 / 应收服务的事务包装） ----------------

    @Bean
    public InventoryPostingPort salesInventoryPostingPort(
            TransactionalInventoryService transactionalInventoryService) {
        return new TransactionalInventoryPostingAdapter(transactionalInventoryService);
    }

    @Bean
    public ReceivablePostingPort salesReceivablePostingPort(ReceivableService receivableService) {
        return new ReceivablePostingAdapter(receivableService);
    }

    // ---------------- 领域服务（各为对应写操作的唯一入口） ----------------

    @Bean
    public ReceivableService receivableService(ReceivableRepository receivableRepository) {
        return new ReceivableService(receivableRepository);
    }

    @Bean
    public SalesOrderService salesOrderService(SalesOrderRepository salesOrderRepository,
                                               DomainEventPublisher domainEventPublisher) {
        return new SalesOrderService(salesOrderRepository, domainEventPublisher);
    }

    @Bean
    public SalesDeliveryService salesDeliveryService(SalesDeliveryRepository salesDeliveryRepository,
                                                     SalesOrderService salesOrderService,
                                                     InventoryPostingPort salesInventoryPostingPort,
                                                     DomainEventPublisher domainEventPublisher) {
        return new SalesDeliveryService(salesDeliveryRepository, salesOrderService,
                salesInventoryPostingPort, domainEventPublisher);
    }

    @Bean
    public SalesInvoiceService salesInvoiceService(SalesInvoiceRepository salesInvoiceRepository,
                                                   SalesDeliveryService salesDeliveryService,
                                                   ReceivablePostingPort salesReceivablePostingPort,
                                                   DomainEventPublisher domainEventPublisher) {
        return new SalesInvoiceService(salesInvoiceRepository, salesDeliveryService,
                salesReceivablePostingPort, domainEventPublisher);
    }
}
