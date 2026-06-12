package com.sjherp.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberRule;
import com.sjherp.domain.partner.CustomerCommand;
import com.sjherp.domain.partner.CustomerRepository;
import com.sjherp.domain.partner.CustomerService;
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.infra.persistence.audit.AuditLogRepository;
import com.sjherp.infra.persistence.audit.JdbcAuditLogRepository;
import com.sjherp.infra.persistence.partner.JdbcCustomerRepository;

/**
 * 幽灵审计防护集成测试（D-8 核心验收，Testcontainers 真实 MySQL）：
 * 用与生产同构的装配（@EnableTransactionManagement + @EnableAspectJAutoProxy +
 * AuditAspect Bean + 真实 Jdbc 仓储）验证事务感知审计写入：
 * <ul>
 *   <li><b>外层事务回滚 → audit_log 无记录</b>（修复前 REQUIRES_NEW 会留下
 *       「有审计无业务」的幽灵记录，这是 D-8 的核心断言）；</li>
 *   <li>外层事务提交 → 审计在提交后写入（事务内查不到，提交后查到）；</li>
 *   <li>无事务路径（现有档案路径）→ 立即写入，行为与修复前一致。</li>
 * </ul>
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无
 * Docker）。CI 的 backend-integration-db job 显式运行：
 * <pre>mvn test -pl sjherp-infra,sjherp-app -Dgroups=integration-db -DexcludedGroups=none</pre>
 */
@Tag("integration-db")
class GhostAuditPreventionIntegrationTest {

    /** MySQL 8.4（与 sjherp-infra 的 MySqlContainerTestBase 同版本约定） */
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static CustomerService customerService;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        DataSource migrationDataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        // 跑 classpath 全部迁移（db/migration 来自 sjherp-infra 依赖）
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        txTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        customerService = context.getBean(CustomerService.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
        // 容器由 Testcontainers Ryuk 自动回收
    }

    /**
     * 与生产同构的最小装配：事务注解驱动（仓储 @Transactional 真实生效，
     * JdbcAuditLogRepository.insert 的 REQUIRES_NEW 同生产）+ AspectJ 自动代理
     * （CustomerService 被 AuditAspect 拦截，匹配语义同 Boot 容器）。
     */
    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AuditMetrics auditMetrics() {
            return new AuditMetrics();
        }

        @Bean
        AuditLogRepository auditLogRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcAuditLogRepository(jdbcTemplate);
        }

        @Bean
        TransactionAwareAuditWriter transactionAwareAuditWriter(AuditLogRepository repository,
                                                                AuditMetrics metrics) {
            return new TransactionAwareAuditWriter(repository, metrics);
        }

        @Bean
        AuditAspect auditAspect(TransactionAwareAuditWriter writer, AuditMetrics metrics) {
            return new AuditAspect(writer, metrics);
        }

        @Bean
        CustomerRepository customerRepository(JdbcTemplate jdbcTemplate) {
            return new JdbcCustomerRepository(jdbcTemplate);
        }

        /** 编号生成桩：测试显式传 code，不触发自动编号（编号规则不在本测试范围） */
        @Bean
        DocumentNumberGenerator numberGenerator() {
            return new DocumentNumberGenerator() {
                @Override
                public String generate(DocumentNumberRule rule) {
                    return "CUS-IT-" + System.nanoTime();
                }

                @Override
                public String generate(DocumentNumberRule rule, YearMonth yearMonth) {
                    return generate(rule);
                }
            };
        }

        @Bean
        CustomerService customerService(CustomerRepository customerRepository,
                                        DocumentNumberGenerator numberGenerator) {
            return new CustomerService(customerRepository, numberGenerator);
        }
    }

    private static CustomerCommand command(String code, String name) {
        return new CustomerCommand(code, name, null, null, null, null,
                SettlementMethod.MONTHLY, null);
    }

    private static String uniqueCode(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private long customerCount(String code) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer WHERE code = ?", Long.class, code);
        return count == null ? -1 : count;
    }

    private long auditCount(String code) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'customer.create' AND target_code = ?",
                Long.class, code);
        return count == null ? -1 : count;
    }

    @Test
    void 外层事务回滚后无幽灵审计() {
        String code = uniqueCode("CUS-IT-RB");

        // 模拟 M3 形态：跨表外层事务包住 @Audited 方法，业务最终回滚
        txTemplate.executeWithoutResult(status -> {
            customerService.create(command(code, "回滚测试客户"), "it-admin");
            status.setRollbackOnly();
        });

        assertThat(customerCount(code)).as("业务回滚后客户行不存在").isZero();
        // D-8 核心断言：修复前 REQUIRES_NEW 会在此留下「有审计无业务」的幽灵记录
        assertThat(auditCount(code)).as("业务回滚后审计必须为空（幽灵审计已修复）").isZero();
    }

    @Test
    void 外层事务提交后审计写入_且事务内尚未写入() {
        String code = uniqueCode("CUS-IT-CM");

        txTemplate.executeWithoutResult(status -> {
            customerService.create(command(code, "提交测试客户"), "it-admin");
            // 事务内：审计延迟到 afterCommit，此刻 audit_log 中还查不到
            assertThat(auditCount(code)).as("事务内审计尚未写入（延迟到提交后）").isZero();
        });

        assertThat(customerCount(code)).as("业务提交后客户行存在").isEqualTo(1);
        assertThat(auditCount(code)).as("业务提交后审计恰好一条").isEqualTo(1);
    }

    @Test
    void 无事务路径立即写入_行为与修复前一致() {
        String code = uniqueCode("CUS-IT-NT");

        // 现有档案路径：领域 Service 无事务，事务在 Jdbc 仓储方法级
        customerService.create(command(code, "无事务测试客户"), "it-admin");

        assertThat(customerCount(code)).isEqualTo(1);
        assertThat(auditCount(code)).as("无活动事务时立即插入").isEqualTo(1);
    }
}
