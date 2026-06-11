package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.warehouse.WarehouseRepository;
import com.sjherp.domain.warehouse.WarehouseService;
import com.sjherp.infra.persistence.warehouse.JdbcWarehouseRepository;

/**
 * 仓库档案（warehouse）装配：仓储 MySQL 实现 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link CatalogInfraConfig}）。单据编号生成器复用 CatalogInfraConfig
 * 中定义的 {@link DocumentNumberGenerator} Bean。
 */
@Configuration
public class WarehouseInfraConfig {

    @Bean
    public WarehouseRepository warehouseRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcWarehouseRepository(jdbcTemplate);
    }

    @Bean
    public WarehouseService warehouseService(WarehouseRepository warehouseRepository,
                                             DocumentNumberGenerator documentNumberGenerator) {
        return new WarehouseService(warehouseRepository, documentNumberGenerator);
    }
}
