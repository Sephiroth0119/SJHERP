package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.production.BillOfMaterialsRepository;
import com.sjherp.domain.production.BillOfMaterialsService;
import com.sjherp.domain.production.DemandPlanRepository;
import com.sjherp.domain.production.DemandPlanService;
import com.sjherp.domain.production.MrpDemandSource;
import com.sjherp.domain.production.MrpInventorySource;
import com.sjherp.domain.production.MrpRunRepository;
import com.sjherp.domain.production.MrpService;
import com.sjherp.domain.production.RoutingRepository;
import com.sjherp.domain.production.RoutingService;
import com.sjherp.infra.persistence.production.JdbcBillOfMaterialsRepository;
import com.sjherp.infra.persistence.production.JdbcDemandPlanRepository;
import com.sjherp.infra.persistence.production.JdbcMrpDemandSource;
import com.sjherp.infra.persistence.production.JdbcMrpRunRepository;
import com.sjherp.infra.persistence.production.JdbcRoutingRepository;

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
}
