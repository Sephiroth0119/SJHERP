package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.partner.CustomerRepository;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SupplierRepository;
import com.sjherp.domain.partner.SupplierService;
import com.sjherp.infra.persistence.partner.JdbcCustomerRepository;
import com.sjherp.infra.persistence.partner.JdbcSupplierRepository;

/**
 * 往来档案（partner：客户/供应商）装配：仓储 MySQL 实现 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link CatalogInfraConfig}）。单据编号生成器复用 CatalogInfraConfig
 * 中定义的 {@link DocumentNumberGenerator} Bean。
 */
@Configuration
public class PartnerInfraConfig {

    // ---------------- 仓储（MySQL 实现） ----------------

    @Bean
    public CustomerRepository customerRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcCustomerRepository(jdbcTemplate);
    }

    @Bean
    public SupplierRepository supplierRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSupplierRepository(jdbcTemplate);
    }

    // ---------------- 领域服务（所有档案写操作的唯一入口） ----------------

    @Bean
    public CustomerService customerService(CustomerRepository customerRepository,
                                           DocumentNumberGenerator documentNumberGenerator) {
        return new CustomerService(customerRepository, documentNumberGenerator);
    }

    @Bean
    public SupplierService supplierService(SupplierRepository supplierRepository,
                                           DocumentNumberGenerator documentNumberGenerator) {
        return new SupplierService(supplierRepository, documentNumberGenerator);
    }
}
