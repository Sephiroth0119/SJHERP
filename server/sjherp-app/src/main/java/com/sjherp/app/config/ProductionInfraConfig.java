package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.production.BillOfMaterialsRepository;
import com.sjherp.domain.production.BillOfMaterialsService;
import com.sjherp.domain.production.RoutingRepository;
import com.sjherp.domain.production.RoutingService;
import com.sjherp.infra.persistence.production.JdbcBillOfMaterialsRepository;
import com.sjherp.infra.persistence.production.JdbcRoutingRepository;

/**
 * 生产模块（BOM + 工艺路线）装配（M5-T01）：仓储 MySQL 实现 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link CatalogInfraConfig}）。
 *
 * <p>{@link ProductRepository} bean 已在 {@link CatalogInfraConfig} 中声明，
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
}
