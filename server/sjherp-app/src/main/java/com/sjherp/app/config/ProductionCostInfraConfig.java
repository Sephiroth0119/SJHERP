package com.sjherp.app.config;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.app.production.ProductionCostSettlementAppService;
import com.sjherp.app.production.ProductionCostVoucherService;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.production.InventoryPostingPort;
import com.sjherp.domain.production.MaterialIssueRepository;
import com.sjherp.domain.production.ProductionCostParamRepository;
import com.sjherp.domain.production.ProductionCostSettlementRepository;
import com.sjherp.domain.production.ProductionCostSettlementService;
import com.sjherp.domain.production.ProductionReportRepository;
import com.sjherp.domain.production.RoutingRepository;
import com.sjherp.domain.production.WorkOrderRepository;
import com.sjherp.infra.persistence.production.JdbcProductionCostParamRepository;
import com.sjherp.infra.persistence.production.JdbcProductionCostSettlementRepository;

/**
 * 生产成本归集与结转（M5-T06）装配——**独立于 {@link ProductionInfraConfig}**。
 *
 * <p>拆出独立配置的原因（评审 CI 修复）：本批的 {@link ProductionCostVoucherService} 依赖
 * GL 域 {@link VoucherService}/{@link AccountingPeriodService}（由 {@link GlInfraConfig} 提供）。
 * 若把这些 bean 放在 ProductionInfraConfig，则任何仅 @Import ProductionInfraConfig 的集成测
 * （M5-T04 领料 / M5-T05 报工 FlowIntegrationTest，不涉及 GL）都会被迫提供 VoucherService 才能
 * 启动上下文——Spring 对 @Import 的 @Configuration 会饿汉式实例化其全部 bean。拆出后：领料/报工
 * 测试只 @Import ProductionInfraConfig 不再受牵连；本配置仅在需要成本结转 + GL 的上下文（生产环境
 * 组件扫描自动装配；T06 FlowIntegrationTest 显式 @Import GlInfraConfig + ProductionInfraConfig +
 * 本配置）中加载。
 *
 * <p>本配置的 bean 跨配置按类型注入 {@link ProductionInfraConfig} 的仓储/端口（workOrderRepository
 * / materialIssueRepository / productionReportRepository / routingRepository /
 * materialIssueInventoryPostingAdapter——同一 Spring 上下文内可见）。
 */
@Configuration
public class ProductionCostInfraConfig {

    @Bean
    public ProductionCostSettlementRepository productionCostSettlementRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcProductionCostSettlementRepository(jdbcTemplate);
    }

    @Bean
    public ProductionCostParamRepository productionCostParamRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcProductionCostParamRepository(jdbcTemplate);
    }

    /**
     * 成本结转领域服务：复用 materialIssueInventoryPostingAdapter（同一 InventoryPostingPort，
     * 完工工费增量走 COST_ADJUST，适配器已支持所有 InventoryTxnType，D6）。
     * 系统级默认人工费率/制造费用率由配置兜底（账期无 production_cost_param 行时，R-T06-1）。
     */
    @Bean
    public ProductionCostSettlementService productionCostSettlementService(
            ProductionCostSettlementRepository productionCostSettlementRepository,
            WorkOrderRepository workOrderRepository,
            MaterialIssueRepository materialIssueRepository,
            ProductionReportRepository productionReportRepository,
            RoutingRepository routingRepository,
            ProductionCostParamRepository productionCostParamRepository,
            InventoryPostingPort materialIssueInventoryPostingAdapter,
            DomainEventPublisher domainEventPublisher,
            @Value("${sjherp.production.default-labor-rate:0}") BigDecimal defaultLaborRate,
            @Value("${sjherp.production.overhead-rate:0}") BigDecimal overheadRate) {
        return new ProductionCostSettlementService(productionCostSettlementRepository,
                workOrderRepository, materialIssueRepository, productionReportRepository,
                routingRepository, productionCostParamRepository, materialIssueInventoryPostingAdapter,
                domainEventPublisher, defaultLaborRate, overheadRate);
    }

    /**
     * 成本结转凭证服务（料/工费归集 + 完工结转，照 AutoVoucherService）。
     * VoucherService / AccountingPeriodService 由 GlInfraConfig 注册，按类型注入。
     */
    @Bean
    public ProductionCostVoucherService productionCostVoucherService(
            VoucherService voucherService,
            AccountingPeriodService accountingPeriodService,
            DocumentNumberGenerator documentNumberGenerator,
            ProductionCostSettlementRepository productionCostSettlementRepository) {
        return new ProductionCostVoucherService(voucherService, accountingPeriodService,
                documentNumberGenerator, productionCostSettlementRepository);
    }

    /** 成本结转应用服务：编排 CostAdjust + GL + 状态，事务边界。 */
    @Bean
    public ProductionCostSettlementAppService productionCostSettlementAppService(
            ProductionCostSettlementService productionCostSettlementService,
            ProductionCostVoucherService productionCostVoucherService,
            DocumentNumberGenerator documentNumberGenerator) {
        return new ProductionCostSettlementAppService(productionCostSettlementService,
                productionCostVoucherService, documentNumberGenerator);
    }
}
