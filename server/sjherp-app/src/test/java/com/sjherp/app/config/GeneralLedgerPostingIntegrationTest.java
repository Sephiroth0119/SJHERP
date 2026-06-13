package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherNotBalancedException;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.infra.persistence.JdbcSequenceProvider;

/**
 * 总账过账整链集成测试（M4-T01 验收①/②核心，Testcontainers 真实 MySQL）：用生产同套装配
 * （@Import 真实 {@link AuditConfig} + {@link GlInfraConfig}）跑通：
 * <ul>
 *   <li>开账 202606 → 建平衡凭证（借 1001 / 贷 6001 各 100.00）→ post 成功（APPROVED）；</li>
 *   <li>关账 202606 → 该期再建+过账凭证<b>被拒（PeriodClosedException）且事务回滚</b>（验收②）；</li>
 *   <li>trialBalance(202606) Σ借 == Σ贷；</li>
 *   <li>不平凭证 create 抛 {@link VoucherNotBalancedException} 且库中无记录（验收①）；</li>
 *   <li>post 后 audit_log 有 voucher.post 记录（CLAUDE.md 原则 3：可审计）。</li>
 * </ul>
 *
 * <p>预置科目（1001/6001 等）经 Flyway V19 迁移就位，可直接引用。各账期/凭证号按测试运行时刻
 * 拼唯一后缀隔离数据（凭证表无外键，自造账期键所属年月）。单据状态流转与过账用
 * {@link TransactionTemplate} 提供外层事务（等价 app AppService 的 @Transactional 边界）——关账期
 * 过账被拒时整事务回滚，断言凭证仍为草稿、库中无被污染数据。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class GeneralLedgerPostingIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static AccountingPeriodService periodService;
    private static VoucherService voucherService;

    @BeforeAll
    static void setUp() {
        MYSQL.start();
        DataSource migrationDataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure()
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        txTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        periodService = context.getBean(AccountingPeriodService.class);
        voucherService = context.getBean(VoucherService.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import({AuditConfig.class, GlInfraConfig.class})
    static class TestConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertyPlaceholder() {
            return new PropertySourcesPlaceholderConfigurer();
        }

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

        // GlInfraConfig 的 AutoVoucherService（M4-T02）依赖编号生成器；此处显式 new 一份
        // （生产由 catalog 装配，此隔离上下文不引入整套档案 Bean 闭包）。
        @Bean
        SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
            return new JdbcSequenceProvider(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
            return new DefaultDocumentNumberGenerator(sequenceProvider);
        }
    }

    // ----------------------------------------------------- 验收②：关账期过账被拒 + 回滚

    @Test
    void 开账建平衡凭证过账成功_关账后再过账被拒且回滚() {
        String suffix = Long.toString(System.nanoTime(), 36);
        // 账期键：用一个独立年月隔离本测试（避免与其他测试共用 202606 计数干扰）
        String period = "202601";
        LocalDate voucherDate = LocalDate.of(2026, 1, 15);
        String vch1 = "VCH-IT1-" + suffix;
        String vch2 = "VCH-IT2-" + suffix;

        // 1. 开账期 202601
        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        assertThat(periodService.isOpen(period)).isTrue();

        // 2. 建平衡凭证（借 1001 贷 6001 各 100.00）→ post 成功（APPROVED）
        txTemplate.executeWithoutResult(s ->
                voucherService.create(vch1, period, voucherDate, "整链测试",
                        List.of(new VoucherLineInput("1001", new BigDecimal("100.00"), null, "现金"),
                                new VoucherLineInput("6001", null, new BigDecimal("100.00"), "收入")),
                        OPERATOR));
        txTemplate.executeWithoutResult(s ->
                assertThat(voucherService.post(vch1, OPERATOR).getStatus())
                        .isEqualTo(DocumentStatus.APPROVED));
        assertThat(jdbc.queryForObject("SELECT status FROM voucher WHERE doc_no = ?",
                String.class, vch1)).isEqualTo("APPROVED");

        // 3. 关账 202601
        txTemplate.executeWithoutResult(s -> periodService.close(period, OPERATOR));
        assertThat(periodService.isOpen(period)).isFalse();

        // 4. 关账期再建+过账凭证 → 被拒（PeriodClosedException）且事务回滚（验收②）
        //    建单本身允许（账期只需存在不要求 OPEN），过账时才被拒——把建单+过账放同一事务，
        //    过账抛异常时整事务回滚，连建单插入的草稿凭证一并撤销，库中无 vch2 任何痕迹。
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> {
                    voucherService.create(vch2, period, voucherDate, "关账期凭证",
                            List.of(new VoucherLineInput("1001", new BigDecimal("50.00"), null, null),
                                    new VoucherLineInput("6001", null, new BigDecimal("50.00"), null)),
                            OPERATOR);
                    voucherService.post(vch2, OPERATOR);
                }))
                .isInstanceOf(PeriodClosedException.class);

        // 回滚验证：vch2 在库中不存在（建单+过账同事务一并回滚，不留半过账/孤儿草稿）
        Long vch2Count = jdbc.queryForObject("SELECT COUNT(*) FROM voucher "
                + "WHERE tenant_id = 0 AND doc_no = ?", Long.class, vch2);
        assertThat(vch2Count).isZero();

        // 5. 试算平衡：本期已过账凭证 Σ借 == Σ贷
        List<AccountBalance> balances = voucherService.trialBalance(period);
        assertThat(balances).isNotEmpty();
        BigDecimal totalDebit = balances.stream().map(AccountBalance::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = balances.stream().map(AccountBalance::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).as("试算平衡 Σ借 = Σ贷").isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).as("仅 vch1 计入（vch2 已回滚）").isEqualByComparingTo("100.00");

        // 6. 审计：post 后 audit_log 有 voucher.post 记录（事务提交后落库）
        Long postAuditCount = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log "
                        + "WHERE action = 'voucher.post' AND target_code = ?",
                Long.class, vch1);
        assertThat(postAuditCount).as("voucher.post 审计记录").isGreaterThanOrEqualTo(1L);
    }

    // ----------------------------------------------------- 验收①：不平凭证 create 抛 + 库中无记录

    @Test
    void 不平凭证建单被拒_库中无记录() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String period = "202602";
        LocalDate voucherDate = LocalDate.of(2026, 2, 10);
        String vch = "VCH-UNBAL-" + suffix;

        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));

        // 借 100 ≠ 贷 80 → Voucher.create 构造时抛 VoucherNotBalancedException（到不了 save，验收①）
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s ->
                        voucherService.create(vch, period, voucherDate, "不平凭证",
                                List.of(new VoucherLineInput("1001", new BigDecimal("100.00"), null, null),
                                        new VoucherLineInput("6001", null, new BigDecimal("80.00"), null)),
                                OPERATOR)))
                .isInstanceOf(VoucherNotBalancedException.class);

        // 库中无该凭证任何记录（连聚合都构造不出，根本到不了仓储）
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM voucher "
                + "WHERE tenant_id = 0 AND doc_no = ?", Long.class, vch);
        assertThat(count).isZero();
        // 凭证行表亦无残留
        Long lineCount = jdbc.queryForObject("SELECT COUNT(*) FROM voucher_line vl "
                        + "JOIN voucher v ON vl.voucher_id = v.id WHERE v.doc_no = ?",
                Long.class, vch);
        assertThat(lineCount).isZero();
    }

    // ----------------------------------------------------- 多借多贷平衡过账 + 试算平衡

    @Test
    void 多借多贷平衡凭证过账_试算平衡成立() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String period = "202603";
        LocalDate voucherDate = LocalDate.of(2026, 3, 20);
        String vch = "VCH-MULTI-" + suffix;

        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        // 借 1001 60 + 1002 40 = 100；贷 6001 70 + 6051 30 = 100
        txTemplate.executeWithoutResult(s ->
                voucherService.create(vch, period, voucherDate, "多借多贷",
                        List.of(new VoucherLineInput("1001", new BigDecimal("60.00"), null, null),
                                new VoucherLineInput("1002", new BigDecimal("40.00"), null, null),
                                new VoucherLineInput("6001", null, new BigDecimal("70.00"), null),
                                new VoucherLineInput("6051", null, new BigDecimal("30.00"), null)),
                        OPERATOR));
        txTemplate.executeWithoutResult(s -> voucherService.post(vch, OPERATOR));

        List<AccountBalance> balances = voucherService.trialBalance(period);
        BigDecimal totalDebit = balances.stream().map(AccountBalance::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = balances.stream().map(AccountBalance::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo("100.00");
        assertThat(totalCredit).isEqualByComparingTo("100.00");
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    }
}
