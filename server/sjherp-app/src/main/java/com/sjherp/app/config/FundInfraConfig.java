package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.fund.PaymentAccountRepository;
import com.sjherp.domain.fund.PaymentAccountService;
import com.sjherp.domain.gl.AccountRepository;
import com.sjherp.infra.persistence.fund.JdbcPaymentAccountRepository;

/**
 * 资金账户档案（payment_account）装配（M4-T04a）：仓储 MySQL 实现 + 领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link WarehouseInfraConfig}）。单据编号生成器复用 CatalogInfraConfig
 * 中定义的 {@link DocumentNumberGenerator} Bean（FA-年月-序号）。
 *
 * <p>与 warehouse 唯一不同：注入 {@link AccountRepository}（GlInfraConfig 装配的 GL 科目仓储），
 * 供 {@link PaymentAccountService} 校验 glAccountCode 为已存在/启用/末级科目。
 */
@Configuration
public class FundInfraConfig {

    @Bean
    public PaymentAccountRepository paymentAccountRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPaymentAccountRepository(jdbcTemplate);
    }

    @Bean
    public PaymentAccountService paymentAccountService(PaymentAccountRepository paymentAccountRepository,
                                                       DocumentNumberGenerator documentNumberGenerator,
                                                       AccountRepository accountRepository) {
        return new PaymentAccountService(paymentAccountRepository, documentNumberGenerator, accountRepository);
    }
}
