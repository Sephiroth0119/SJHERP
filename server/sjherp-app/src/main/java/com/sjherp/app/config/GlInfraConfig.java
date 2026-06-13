package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.common.event.DomainEventPublisher;
import com.sjherp.domain.gl.AccountRepository;
import com.sjherp.domain.gl.AccountService;
import com.sjherp.domain.gl.AccountingPeriodRepository;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.VoucherRepository;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.infra.persistence.gl.JdbcAccountRepository;
import com.sjherp.infra.persistence.gl.JdbcAccountingPeriodRepository;
import com.sjherp.infra.persistence.gl.JdbcVoucherRepository;

/**
 * 总账线装配（M4-T01，路线图 §6，全系统最高风险的财务核心）：科目 / 账期 / 凭证仓储 MySQL 实现
 * + 三个领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link PurchaseInfraConfig} / {@link SalesInfraConfig}）。
 *
 * <p><b>事务与审计的装配关系</b>：
 * <ul>
 *   <li>外层事务由 app 的 {@code AccountAppService} / {@code AccountingPeriodAppService} /
 *       {@code VoucherAppService}（@Transactional 写方法）提供；</li>
 *   <li>三个领域服务不加事务（保持可独立测试），其 @Audited 写方法由 AuditAspect 自动代理
 *       （注册为 Bean 即被代理）；凭证状态流转经注入的 {@link DomainEventPublisher} 自动落
 *       document.status_changed 审计；</li>
 *   <li>凭证过账的关账守卫（账期 OPEN 校验）在 {@link VoucherService#post} 内，与状态变更同一外层
 *       事务——账期已关账时抛 PeriodClosedException（409）并回滚（验收②），不会出现"半过账"。</li>
 * </ul>
 *
 * <p>凭证号 VCH-yyyyMM-序号由 app 层 {@code VoucherAppService} 经 CatalogInfraConfig 注册的
 * {@code DocumentNumberGenerator} 生成（按凭证日期所属年月段计序）。
 */
@Configuration
public class GlInfraConfig {

    // ---------------- 仓储（MySQL 实现） ----------------

    @Bean
    public AccountRepository accountRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAccountRepository(jdbcTemplate);
    }

    @Bean
    public AccountingPeriodRepository accountingPeriodRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAccountingPeriodRepository(jdbcTemplate);
    }

    @Bean
    public VoucherRepository voucherRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcVoucherRepository(jdbcTemplate);
    }

    // ---------------- 领域服务（各总账写操作的唯一入口） ----------------

    @Bean
    public AccountService accountService(AccountRepository accountRepository) {
        return new AccountService(accountRepository);
    }

    @Bean
    public AccountingPeriodService accountingPeriodService(
            AccountingPeriodRepository accountingPeriodRepository) {
        return new AccountingPeriodService(accountingPeriodRepository);
    }

    @Bean
    public VoucherService voucherService(VoucherRepository voucherRepository,
                                         AccountService accountService,
                                         AccountingPeriodService accountingPeriodService,
                                         DomainEventPublisher domainEventPublisher) {
        return new VoucherService(voucherRepository, accountService, accountingPeriodService,
                domainEventPublisher);
    }
}
