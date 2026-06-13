package com.sjherp.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.receivable.ReceivableRepository;
import com.sjherp.domain.settlement.SettlementRecordRepository;
import com.sjherp.domain.settlement.SettlementService;
import com.sjherp.infra.persistence.settlement.JdbcSettlementRecordRepository;

/**
 * 核销引擎装配（M4-T03，路线图 §6）：核销记录仓储 MySQL 实现 + 核销引擎领域服务。
 *
 * <p>domain/infra 的类不加 Spring 注解（保持可独立测试），统一在此显式装配
 * （约定同 {@link SalesInfraConfig} / {@link PurchaseInfraConfig}）。
 *
 * <p><b>事务与审计的装配关系</b>：
 * <ul>
 *   <li>核销引擎 {@link SettlementService} 不加事务（保持可独立测试），其 @Audited 写方法
 *       （settleReceivable/settlePayable）由 AuditAspect 自动代理（注册为 Bean 即被代理）；</li>
 *   <li>外层事务边界由<b>触发方</b>提供——本批核销引擎无生产触发器（设计真源 §0：收付款单是 M4-T04），
 *       届时 T04 收付款单 AppService 的 @Transactional 写方法把「核销 + 现金侧凭证」包成原子事务；</li>
 *   <li>核销引擎注入既有的 {@link ReceivableRepository}（SalesInfraConfig）+
 *       {@link AccountsPayableRepository}（PurchaseInfraConfig）+ 本类新装的
 *       {@link SettlementRecordRepository}（按参数注入，Spring 按类型解析既有 Bean）。</li>
 * </ul>
 *
 * <p>账龄只读 DAO（{@code AgingReportDao}）走 @Repository 自动扫描；核销历史只读应用服务
 * （{@code SettlementReadAppService}）与各 Controller 走组件扫描——均不在此装配。
 */
@Configuration
public class SettlementInfraConfig {

    /** 核销记录仓储（MySQL 实现，只追加）。 */
    @Bean
    public SettlementRecordRepository settlementRecordRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSettlementRecordRepository(jdbcTemplate);
    }

    /** 核销引擎（注入既有应收/应付仓储 + 新核销记录仓储；@Audited 由 AuditAspect 代理）。 */
    @Bean
    public SettlementService settlementService(ReceivableRepository receivableRepository,
                                               AccountsPayableRepository accountsPayableRepository,
                                               SettlementRecordRepository settlementRecordRepository) {
        return new SettlementService(receivableRepository, accountsPayableRepository,
                settlementRecordRepository);
    }
}
