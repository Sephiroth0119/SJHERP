package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.app.production.KittingCheckAppService;
import com.sjherp.app.production.MaterialIssueAppService;
import com.sjherp.app.production.MaterialReturnAppService;
import com.sjherp.app.production.ProductionReportAppService;
import com.sjherp.domain.production.BillOfMaterialsRepository;
import com.sjherp.domain.production.BillOfMaterialsService;
import com.sjherp.domain.production.DemandPlanRepository;
import com.sjherp.domain.production.DemandPlanService;
import com.sjherp.domain.production.InventoryAvailabilityPort;
import com.sjherp.domain.production.InventoryPostingPort;
import com.sjherp.domain.production.KittingCheckService;
import com.sjherp.domain.production.MaterialIssueRepository;
import com.sjherp.domain.production.MaterialIssueService;
import com.sjherp.domain.production.MaterialReturnRepository;
import com.sjherp.domain.production.MaterialReturnService;
import com.sjherp.domain.production.ProductionReportRepository;
import com.sjherp.domain.production.ProductionReportService;
import com.sjherp.domain.production.MrpDemandSource;
import com.sjherp.domain.production.MrpInventorySource;
import com.sjherp.domain.production.MrpRunRepository;
import com.sjherp.domain.production.MrpService;
import com.sjherp.domain.production.RoutingRepository;
import com.sjherp.domain.production.RoutingService;
import com.sjherp.domain.production.WorkOrderRepository;
import com.sjherp.domain.production.WorkOrderService;
import com.sjherp.infra.persistence.production.JdbcBillOfMaterialsRepository;
import com.sjherp.infra.persistence.production.JdbcDemandPlanRepository;
import com.sjherp.infra.persistence.production.JdbcMaterialIssueRepository;
import com.sjherp.infra.persistence.production.JdbcMaterialReturnRepository;
import com.sjherp.infra.persistence.production.JdbcMrpDemandSource;
import com.sjherp.infra.persistence.production.JdbcMrpRunRepository;
import com.sjherp.infra.persistence.production.JdbcProductionReportRepository;
import com.sjherp.infra.persistence.production.JdbcRoutingRepository;
import com.sjherp.infra.persistence.production.JdbcWorkOrderRepository;

/**
 * 生产模块（BOM + 工艺路线 + 需求计划 + MRP）装配（M5-T01/T02）：仓储 MySQL 实现 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link CatalogInfraConfig}）。
 *
 * <p>{@link ProductRepository} bean 已在 {@link CatalogInfraConfig} 中声明，
 * {@link TransactionalInventoryService} bean 已在 {@link InventoryInfraConfig} 中声明，
 * Spring 按类型自动注入，无需重复定义。
 */
@Configuration
public class ProductionInfraConfig {

    @Bean
    public BillOfMaterialsRepository billOfMaterialsRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcBillOfMaterialsRepository(jdbcTemplate);
    }

    @Bean
    public RoutingRepository routingRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRoutingRepository(jdbcTemplate);
    }

    @Bean
    public BillOfMaterialsService billOfMaterialsService(
            BillOfMaterialsRepository billOfMaterialsRepository,
            ProductRepository productRepository) {
        return new BillOfMaterialsService(billOfMaterialsRepository, productRepository);
    }

    @Bean
    public RoutingService routingService(
            RoutingRepository routingRepository,
            ProductRepository productRepository) {
        return new RoutingService(routingRepository, productRepository);
    }

    /**
     * BOM 服务事务包装（评审 P1）：create/enable 多次仓储写须单一外层事务原子完成。
     * 调用方（控制器）注入本类而非领域服务本身。
     */
    @Bean
    public TransactionalBomService transactionalBomService(BillOfMaterialsService billOfMaterialsService) {
        return new TransactionalBomService(billOfMaterialsService);
    }

    /** 工艺路线服务事务包装（评审 P1），同上。 */
    @Bean
    public TransactionalRoutingService transactionalRoutingService(RoutingService routingService) {
        return new TransactionalRoutingService(routingService);
    }

    // ----------------------------------------------------------------- M5-T02 需求计划 + MRP

    @Bean
    public DemandPlanRepository demandPlanRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcDemandPlanRepository(jdbcTemplate);
    }

    @Bean
    public MrpRunRepository mrpRunRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMrpRunRepository(jdbcTemplate);
    }

    @Bean
    public MrpDemandSource mrpDemandSource(JdbcTemplate jdbcTemplate) {
        return new JdbcMrpDemandSource(jdbcTemplate);
    }

    @Bean
    public DemandPlanService demandPlanService(
            DemandPlanRepository demandPlanRepository,
            ProductRepository productRepository,
            DocumentNumberGenerator documentNumberGenerator) {
        return new DemandPlanService(demandPlanRepository, productRepository, documentNumberGenerator);
    }

    /**
     * 跨领域桥接器：将存货域的 {@link TransactionalInventoryService} 适配为
     * 生产域的 {@link MrpInventorySource} 端口（两个领域不可直接依赖）。
     */
    @Bean
    public MrpInventorySource mrpInventorySource(TransactionalInventoryService transactionalInventoryService) {
        return new MrpInventorySourceAdapter(transactionalInventoryService);
    }

    @Bean
    public MrpService mrpService(
            BillOfMaterialsRepository billOfMaterialsRepository,
            DemandPlanRepository demandPlanRepository,
            ProductRepository productRepository,
            MrpDemandSource mrpDemandSource,
            MrpInventorySource mrpInventorySource,
            MrpRunRepository mrpRunRepository,
            DocumentNumberGenerator documentNumberGenerator) {
        return new MrpService(billOfMaterialsRepository, demandPlanRepository, productRepository,
                mrpDemandSource, mrpInventorySource, mrpRunRepository, documentNumberGenerator);
    }

    /** 需求计划服务事务包装：控制器注入本类而非领域服务本身。 */
    @Bean
    public TransactionalDemandPlanService transactionalDemandPlanService(
            DemandPlanService demandPlanService) {
        return new TransactionalDemandPlanService(demandPlanService);
    }

    /** MRP 服务事务包装：控制器注入本类而非领域服务本身。 */
    @Bean
    public TransactionalMrpService transactionalMrpService(
            MrpService mrpService,
            MrpRunRepository mrpRunRepository) {
        return new TransactionalMrpService(mrpService, mrpRunRepository);
    }

    // ----------------------------------------------------------------- M5-T03 生产工单

    @Bean
    public WorkOrderRepository workOrderRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcWorkOrderRepository(jdbcTemplate);
    }

    @Bean
    public WorkOrderService workOrderService(
            WorkOrderRepository workOrderRepository,
            MrpRunRepository mrpRunRepository,
            BillOfMaterialsRepository billOfMaterialsRepository,
            DocumentNumberGenerator documentNumberGenerator,
            DomainEventPublisher domainEventPublisher) {
        return new WorkOrderService(workOrderRepository, mrpRunRepository,
                billOfMaterialsRepository, documentNumberGenerator, domainEventPublisher);
    }

    /** 工单服务事务包装：控制器注入本类而非领域服务本身。 */
    @Bean
    public TransactionalWorkOrderService transactionalWorkOrderService(
            WorkOrderService workOrderService) {
        return new TransactionalWorkOrderService(workOrderService);
    }

    // ----------------------------------------------------------------- M5-T04 JIT 领料/退料/齐套

    @Bean
    public MaterialIssueRepository materialIssueRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMaterialIssueRepository(jdbcTemplate);
    }

    @Bean
    public MaterialReturnRepository materialReturnRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMaterialReturnRepository(jdbcTemplate);
    }

    /**
     * 库存过账端口适配器（领料/退料 → 进销存域唯一入口）。
     * 将 {@link TransactionalInventoryService} 适配为生产域的 {@link InventoryPostingPort}。
     */
    @Bean
    public InventoryPostingPort materialIssueInventoryPostingAdapter(
            TransactionalInventoryService transactionalInventoryService) {
        return new MaterialIssueInventoryPostingAdapter(transactionalInventoryService);
    }

    /**
     * 库存可用量查询端口适配器（齐套检查用，只读）。
     * 将 {@link TransactionalInventoryService} 适配为生产域的 {@link InventoryAvailabilityPort}。
     */
    @Bean
    public InventoryAvailabilityPort materialIssueAvailabilityAdapter(
            TransactionalInventoryService transactionalInventoryService) {
        return new MaterialIssueAvailabilityAdapter(transactionalInventoryService);
    }

    @Bean
    public MaterialIssueService materialIssueService(
            MaterialIssueRepository materialIssueRepository,
            WorkOrderRepository workOrderRepository,
            InventoryPostingPort materialIssueInventoryPostingAdapter,
            DomainEventPublisher domainEventPublisher) {
        return new MaterialIssueService(materialIssueRepository, workOrderRepository,
                materialIssueInventoryPostingAdapter, domainEventPublisher);
    }

    @Bean
    public MaterialReturnService materialReturnService(
            MaterialReturnRepository materialReturnRepository,
            MaterialIssueRepository materialIssueRepository,
            InventoryPostingPort materialIssueInventoryPostingAdapter,
            DomainEventPublisher domainEventPublisher) {
        return new MaterialReturnService(materialReturnRepository, materialIssueRepository,
                materialIssueInventoryPostingAdapter, domainEventPublisher);
    }

    @Bean
    public KittingCheckService kittingCheckService(
            BillOfMaterialsRepository billOfMaterialsRepository,
            InventoryAvailabilityPort materialIssueAvailabilityAdapter) {
        return new KittingCheckService(billOfMaterialsRepository, materialIssueAvailabilityAdapter);
    }

    /** 领料单应用服务：编排编号生成 + 领域委托，事务边界。 */
    @Bean
    public MaterialIssueAppService materialIssueAppService(
            MaterialIssueService materialIssueService,
            DocumentNumberGenerator documentNumberGenerator) {
        return new MaterialIssueAppService(materialIssueService, documentNumberGenerator);
    }

    /** 退料单应用服务：编排编号生成 + 领域委托，事务边界。 */
    @Bean
    public MaterialReturnAppService materialReturnAppService(
            MaterialReturnService materialReturnService,
            DocumentNumberGenerator documentNumberGenerator) {
        return new MaterialReturnAppService(materialReturnService, documentNumberGenerator);
    }

    /** 齐套检查应用服务：只读，加载工单后调 KittingCheckService 计算。 */
    @Bean
    public KittingCheckAppService kittingCheckAppService(
            WorkOrderService workOrderService,
            KittingCheckService kittingCheckService) {
        return new KittingCheckAppService(workOrderService, kittingCheckService);
    }

    // ----------------------------------------------------------------- M5-T05 报工与完工入库

    @Bean
    public ProductionReportRepository productionReportRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcProductionReportRepository(jdbcTemplate);
    }

    /**
     * 报工单领域服务：复用 materialIssueInventoryPostingAdapter（同一 InventoryPostingPort 实现，
     * 报工完工入库走 PRODUCTION_IN 类型，领料/退料走 PRODUCTION_ISSUE/PRODUCTION_RETURN，
     * 端口适配器已支持所有 InventoryTxnType，无需另建适配器，D7）。
     */
    @Bean
    public ProductionReportService productionReportService(
            ProductionReportRepository productionReportRepository,
            InventoryPostingPort materialIssueInventoryPostingAdapter,
            WorkOrderRepository workOrderRepository,
            MaterialIssueRepository materialIssueRepository,
            DomainEventPublisher domainEventPublisher) {
        return new ProductionReportService(productionReportRepository,
                materialIssueInventoryPostingAdapter,
                workOrderRepository,
                materialIssueRepository,
                domainEventPublisher);
    }

    /** 报工单应用服务：编排编号生成 + 领域委托，事务边界。 */
    @Bean
    public ProductionReportAppService productionReportAppService(
            ProductionReportService productionReportService,
            DocumentNumberGenerator documentNumberGenerator) {
        return new ProductionReportAppService(productionReportService, documentNumberGenerator);
    }
}
