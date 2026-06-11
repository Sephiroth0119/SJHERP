package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.catalog.CategoryRepository;
import com.sjherp.domain.catalog.CategoryService;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.catalog.ProductService;
import com.sjherp.domain.catalog.UnitRepository;
import com.sjherp.domain.catalog.UnitService;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.infra.persistence.JdbcSequenceProvider;
import com.sjherp.infra.persistence.catalog.JdbcCategoryRepository;
import com.sjherp.infra.persistence.catalog.JdbcProductRepository;
import com.sjherp.infra.persistence.catalog.JdbcUnitRepository;

/**
 * 商品档案（catalog）装配：仓储 MySQL 实现 + 领域服务 + 单据编号生成器。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link AgentInfraConfig}）。@Transactional 注解由 Spring Boot
 * 自动配置的事务代理生效。
 */
@Configuration
public class CatalogInfraConfig {

    // ---------------- 仓储（MySQL 实现） ----------------

    @Bean
    public UnitRepository unitRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcUnitRepository(jdbcTemplate);
    }

    @Bean
    public CategoryRepository categoryRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCategoryRepository(jdbcTemplate);
    }

    @Bean
    public ProductRepository productRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcProductRepository(jdbcTemplate);
    }

    // ---------------- 单据编号（M2-T01 数据库序号供给落地） ----------------

    /** 序号供给：doc_sequence 表行锁递增，并发安全、重启不重号 */
    @Bean
    public SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcSequenceProvider(jdbcTemplate);
    }

    @Bean
    public DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
        return new DefaultDocumentNumberGenerator(sequenceProvider);
    }

    // ---------------- 领域服务（所有档案写操作的唯一入口） ----------------

    @Bean
    public UnitService unitService(UnitRepository unitRepository, ProductRepository productRepository) {
        return new UnitService(unitRepository, productRepository);
    }

    @Bean
    public CategoryService categoryService(CategoryRepository categoryRepository,
                                           ProductRepository productRepository) {
        return new CategoryService(categoryRepository, productRepository);
    }

    @Bean
    public ProductService productService(ProductRepository productRepository,
                                         CategoryRepository categoryRepository,
                                         UnitRepository unitRepository,
                                         DocumentNumberGenerator documentNumberGenerator) {
        return new ProductService(productRepository, categoryRepository, unitRepository,
                documentNumberGenerator);
    }
}
