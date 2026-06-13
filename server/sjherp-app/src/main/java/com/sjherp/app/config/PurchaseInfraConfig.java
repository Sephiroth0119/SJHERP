package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.purchase.AccountsPayablePortAdapter;
import com.sjherp.app.purchase.PurchaseInventoryPostingAdapter;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.purchase.AccountsPayablePort;
import com.sjherp.domain.purchase.InventoryPostingPort;
import com.sjherp.domain.purchase.PurchaseInvoiceRepository;
import com.sjherp.domain.purchase.PurchaseInvoiceService;
import com.sjherp.domain.purchase.PurchaseOrderRepository;
import com.sjherp.domain.purchase.PurchaseOrderService;
import com.sjherp.domain.purchase.PurchaseReceiptRepository;
import com.sjherp.domain.purchase.PurchaseReceiptService;
import com.sjherp.infra.persistence.payable.JdbcAccountsPayableRepository;
import com.sjherp.infra.persistence.purchase.JdbcPurchaseInvoiceRepository;
import com.sjherp.infra.persistence.purchase.JdbcPurchaseOrderRepository;
import com.sjherp.infra.persistence.purchase.JdbcPurchaseReceiptRepository;

/**
 * 采购线装配（M3-T05/T06/T07，路线图 §5）：采购订单 / 采购入库单 / 采购发票 / 应付仓储 MySQL 实现
 * + 库存过账端口适配 + 应付端口适配 + 三个领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link TransferInfraConfig} / {@link StocktakeInfraConfig} / {@link InventoryInfraConfig}）。
 *
 * <p><b>事务与审计的装配关系</b>：
 * <ul>
 *   <li>外层事务由 app 的 {@code PurchaseOrderAppService} / {@code PurchaseReceiptAppService} /
 *       {@code PurchaseInvoiceAppService}（@Transactional 写方法）提供；</li>
 *   <li>三个领域服务不加事务（保持可独立测试），其 @Audited 写方法由 AuditAspect 自动代理
 *       （注册为 Bean 即被代理）；状态流转经注入的 {@link DomainEventPublisher} 自动落审计；</li>
 *   <li>采购入库过账经 {@link InventoryPostingPort} → {@link TransactionalInventoryService}
 *       （REQUIRED 加入外层事务），库存两表唯一写入口不被绕过（CLAUDE.md 原则 1）；
 *       同事务回写采购订单到货量（{@code PurchaseReceiptService} 经 {@code PurchaseOrderService}）；</li>
 *   <li>采购发票过账经 {@link AccountsPayablePort} 生成应付，与发票状态变更同事务原子提交。</li>
 * </ul>
 *
 * <p>Agent 工具（create_purchase_order / query_purchase_order）注册见 {@code DomainToolConfig}
 * （显式 new 列表模式，注册片段随该类维护）。
 */
@Configuration
public class PurchaseInfraConfig {

    // ---------------- 仓储（MySQL 实现） ----------------

    @Bean
    public PurchaseOrderRepository purchaseOrderRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPurchaseOrderRepository(jdbcTemplate);
    }

    @Bean
    public PurchaseReceiptRepository purchaseReceiptRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPurchaseReceiptRepository(jdbcTemplate);
    }

    @Bean
    public PurchaseInvoiceRepository purchaseInvoiceRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPurchaseInvoiceRepository(jdbcTemplate);
    }

    @Bean
    public AccountsPayableRepository accountsPayableRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAccountsPayableRepository(jdbcTemplate);
    }

    // ---------------- 端口适配（转调库存唯一写入口 / 应付仓储） ----------------

    @Bean
    public InventoryPostingPort purchaseInventoryPostingPort(
            TransactionalInventoryService transactionalInventoryService) {
        return new PurchaseInventoryPostingAdapter(transactionalInventoryService);
    }

    @Bean
    public AccountsPayablePort accountsPayablePort(AccountsPayableRepository accountsPayableRepository) {
        return new AccountsPayablePortAdapter(accountsPayableRepository);
    }

    // ---------------- 领域服务（各采购写操作的唯一入口） ----------------

    @Bean
    public PurchaseOrderService purchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                                     DomainEventPublisher domainEventPublisher) {
        return new PurchaseOrderService(purchaseOrderRepository, domainEventPublisher);
    }

    @Bean
    public PurchaseReceiptService purchaseReceiptService(PurchaseReceiptRepository purchaseReceiptRepository,
                                                         PurchaseOrderService purchaseOrderService,
                                                         InventoryPostingPort purchaseInventoryPostingPort,
                                                         DomainEventPublisher domainEventPublisher) {
        return new PurchaseReceiptService(purchaseReceiptRepository, purchaseOrderService,
                purchaseInventoryPostingPort, domainEventPublisher);
    }

    @Bean
    public PurchaseInvoiceService purchaseInvoiceService(PurchaseInvoiceRepository purchaseInvoiceRepository,
                                                         PurchaseReceiptService purchaseReceiptService,
                                                         AccountsPayablePort accountsPayablePort,
                                                         DomainEventPublisher domainEventPublisher) {
        return new PurchaseInvoiceService(purchaseInvoiceRepository, purchaseReceiptService,
                accountsPayablePort, domainEventPublisher);
    }
}
