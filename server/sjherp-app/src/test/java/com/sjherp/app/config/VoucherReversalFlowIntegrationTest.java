package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

import com.sjherp.app.gl.VoucherAppService;
import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.IllegalStateTransitionException;
import com.sjherp.domain.common.numbering.DefaultDocumentNumberGenerator;
import com.sjherp.domain.common.numbering.DocumentNumberGenerator;
import com.sjherp.domain.common.numbering.SequenceProvider;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.AccountingPeriodService;
import com.sjherp.domain.gl.PeriodClosedException;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLineInput;
import com.sjherp.domain.gl.VoucherService;
import com.sjherp.domain.gl.VoucherSourceType;
import com.sjherp.infra.persistence.JdbcSequenceProvider;

/**
 * 凭证红冲整链集成测试（M4-T07a 验收核心，Testcontainers 真实 MySQL）：用生产同套装配
 * （@Import 真实 {@link AuditConfig} + {@link GlInfraConfig} + 显式 {@link VoucherAppService}）
 * 跑通凭证红冲基元从过账到冲销、双向 linkage、试算归零、幂等/账期守卫与审计的完整闭环：
 *
 * <ul>
 *   <li>① 开账期 → 建平衡凭证（借 1001 / 贷 6001 各 100.00）→ post（APPROVED）；</li>
 *   <li>② reverse → 红字凭证 APPROVED（借 6001 / 贷 1001 各 100.00，借贷对调）、来源回填
 *       VOUCHER_REVERSAL/原号、reversal_of_id=原号、原凭证 status=REVERSED + reversed_by_id=红字号
 *       （直查 DB 两列验双向 linkage 落库）；</li>
 *   <li>③ trialBalance：1001 与 6001 净额各归零（原 + 红冲抵消）、Σ借 == Σ贷；</li>
 *   <li>④ 再 reverse 原凭证 → 被拒（已冲销 IllegalState）且无新红字；</li>
 *   <li>⑤ 关账后对另一已过账凭证 reverse → PeriodClosedException 且回滚（无红字残留、原凭证仍
 *       APPROVED）；</li>
 *   <li>⑥ audit_log 有 voucher.reverse 记录（CLAUDE.md 原则 3：可审计）。</li>
 * </ul>
 *
 * <p>红冲数学（拆解 §1.1）：红字每行 debit↔credit 对调、金额不变；试算平衡里原凭证科目（1001/6001）
 * 借贷净额归零；Σ借==Σ贷守恒、行数与原一致。金额一律 {@code isEqualByComparingTo}。
 *
 * <p>装配蓝本：{@link GeneralLedgerPostingIntegrationTest}。红字号由 app 层
 * {@link VoucherAppService#reverse} 按原凭证日期年月段经 numberGenerator 预生成传入领域，故测试
 * 装配显式 new 一份 VoucherAppService（领域 reverse 不依赖 numberGenerator，分层一致）。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class VoucherReversalFlowIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-admin";

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate txTemplate;
    private static AccountingPeriodService periodService;
    private static VoucherService voucherService;
    private static VoucherAppService voucherAppService;

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
        voucherAppService = context.getBean(VoucherAppService.class);
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
    @Import({AuditConfig.class, GlInfraConfig.class, ProductRepositoryTestConfig.class})
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

        @Bean
        SequenceProvider sequenceProvider(JdbcTemplate jdbcTemplate) {
            return new JdbcSequenceProvider(jdbcTemplate);
        }

        @Bean
        DocumentNumberGenerator documentNumberGenerator(SequenceProvider sequenceProvider) {
            return new DefaultDocumentNumberGenerator(sequenceProvider);
        }

        // app 层凭证服务：红字号生成 + 委托领域 reverse（@Transactional 外层事务边界，
        // 与生产 VoucherAppService 同一类——红字号按原凭证日期年月段经 numberGenerator 预生成）。
        @Bean
        VoucherAppService voucherAppService(VoucherService voucherService,
                                            DocumentNumberGenerator documentNumberGenerator) {
            return new VoucherAppService(voucherService, documentNumberGenerator);
        }
    }

    // ----------------------------------------------------- 主链：过账→红冲→双向 linkage→试算归零→幂等

    @Test
    void 过账凭证红冲_借贷对调双向linkage_试算归零_再冲销被拒() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String period = "202607";
        LocalDate voucherDate = LocalDate.of(2026, 7, 15);
        String vch = "VCH-REV-" + suffix;

        // 1. 开账期 + 建平衡凭证（借 1001 / 贷 6001 各 100.00）+ 过账（APPROVED）
        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                voucherService.create(vch, period, voucherDate, "红冲整链原始凭证",
                        List.of(new VoucherLineInput("1001", new BigDecimal("100.00"), null, "现金"),
                                new VoucherLineInput("6001", null, new BigDecimal("100.00"), "收入")),
                        OPERATOR));
        txTemplate.executeWithoutResult(s ->
                assertThat(voucherService.post(vch, OPERATOR).getStatus())
                        .isEqualTo(DocumentStatus.APPROVED));

        // 2. reverse（app 层生成红字号 → 领域对调过账）
        Voucher red = txTemplate.execute(s -> voucherAppService.reverse(vch, OPERATOR));
        assertThat(red).isNotNull();
        String redDocNo = red.getDocNo();

        // 红字凭证：APPROVED + 来源 VOUCHER_REVERSAL/原号 + reversalOf=原号 + isReversalDocument
        assertThat(red.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(red.getSourceDocType()).isEqualTo(VoucherSourceType.VOUCHER_REVERSAL.name());
        assertThat(red.getSourceDocNo()).isEqualTo(vch);
        assertThat(red.getReversalOfId()).isEqualTo(vch);
        assertThat(red.isReversalDocument()).isTrue();
        // 红字凭证账期/日期沿用原凭证；总额不变
        assertThat(red.getPeriod()).isEqualTo(period);
        assertThat(red.getVoucherDate()).isEqualTo(voucherDate);
        assertThat(red.getTotalAmount()).isEqualByComparingTo("100.00");
        // 行数与原一致；借贷对调（原 1001 借 / 6001 贷 → 红字 1001 贷 / 6001 借）
        assertThat(red.getLines()).hasSize(2);
        var line1001 = red.getLines().stream()
                .filter(l -> l.getAccountCode().equals("1001")).findFirst().orElseThrow();
        var line6001 = red.getLines().stream()
                .filter(l -> l.getAccountCode().equals("6001")).findFirst().orElseThrow();
        assertThat(line1001.getCredit()).as("原 1001 借→红字贷").isEqualByComparingTo("100.00");
        assertThat(line1001.getDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line6001.getDebit()).as("原 6001 贷→红字借").isEqualByComparingTo("100.00");
        assertThat(line6001.getCredit()).isEqualByComparingTo(BigDecimal.ZERO);

        // 直查 DB 验双向 linkage 落库：红字 reversal_of_id=原号、原凭证 status=REVERSED + reversed_by_id=红字号
        Map<String, Object> redRow = jdbc.queryForMap(
                "SELECT status, source_doc_type, source_doc_no, reversal_of_id, reversed_by_id "
                        + "FROM voucher WHERE tenant_id = 0 AND doc_no = ?", redDocNo);
        assertThat(redRow.get("status")).isEqualTo("APPROVED");
        assertThat(redRow.get("source_doc_type")).isEqualTo(VoucherSourceType.VOUCHER_REVERSAL.name());
        assertThat(redRow.get("source_doc_no")).isEqualTo(vch);
        assertThat(redRow.get("reversal_of_id")).isEqualTo(vch);
        assertThat(redRow.get("reversed_by_id")).as("红字凭证本身未被冲销").isNull();

        Map<String, Object> origRow = jdbc.queryForMap(
                "SELECT status, reversed_by_id, reversal_of_id "
                        + "FROM voucher WHERE tenant_id = 0 AND doc_no = ?", vch);
        assertThat(origRow.get("status")).as("原凭证→已冲销").isEqualTo("REVERSED");
        assertThat(origRow.get("reversed_by_id")).as("原凭证回填红字号").isEqualTo(redDocNo);
        assertThat(origRow.get("reversal_of_id")).as("原凭证非红字单").isNull();

        // 3. 试算平衡：1001 与 6001 净额各归零（原 + 红冲抵消）、Σ借 == Σ贷
        List<AccountBalance> balances = voucherService.trialBalance(period);
        AccountBalance b1001 = balances.stream()
                .filter(b -> b.accountCode().equals("1001")).findFirst().orElseThrow();
        AccountBalance b6001 = balances.stream()
                .filter(b -> b.accountCode().equals("6001")).findFirst().orElseThrow();
        // 1001：原借 100 + 红字贷 100 → 借贷各 100，净额（借-贷）=0
        assertThat(nz(b1001.totalDebit()).subtract(nz(b1001.totalCredit())))
                .as("1001 净额归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(nz(b6001.totalDebit()).subtract(nz(b6001.totalCredit())))
                .as("6001 净额归零").isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal totalDebit = balances.stream().map(b -> nz(b.totalDebit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = balances.stream().map(b -> nz(b.totalCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).as("试算 Σ借 = Σ贷").isEqualByComparingTo(totalCredit);

        // 4. 再 reverse 原凭证 → 被拒（已冲销 IllegalState），且无新红字（仍只有一张红字）
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> voucherAppService.reverse(vch, OPERATOR)))
                .isInstanceOf(IllegalStateException.class);
        Long redCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher WHERE tenant_id = 0 "
                        + "AND source_doc_type = 'VOUCHER_REVERSAL' AND source_doc_no = ?",
                Long.class, vch);
        assertThat(redCount).as("每张原凭证至多一张红冲").isEqualTo(1L);

        // 6. 审计：①voucher.reverse 记录（@Audited 取返回值红字凭证为目标，故 target_code=红字号——
        //    AuditAspect 用返回值作 AuditTarget，reverse 返回红字凭证）；②原凭证被冲销另经
        //    document.status_changed 落审计（target_code=原号，APPROVED→REVERSED），保留"谁被冲销"直觉追溯。
        Long reverseAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'voucher.reverse' AND target_code = ?",
                Long.class, redDocNo);
        assertThat(reverseAudit).as("voucher.reverse 审计记录（目标=红字号）").isGreaterThanOrEqualTo(1L);
        Long origReversedAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'document.status_changed' AND target_code = ?",
                Long.class, vch);
        assertThat(origReversedAudit).as("原凭证冲销经 document.status_changed 可追溯").isGreaterThanOrEqualTo(1L);
    }

    // ----------------------------------------------------- 账期守卫：关账期冲销被拒 + 回滚

    @Test
    void 关账期冲销已过账凭证_被拒且回滚无红字残留() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String period = "202608";
        LocalDate voucherDate = LocalDate.of(2026, 8, 20);
        String vch = "VCH-CLS-" + suffix;

        // 开账 + 建 + 过账
        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        txTemplate.executeWithoutResult(s ->
                voucherService.create(vch, period, voucherDate, "关账期冲销测试",
                        List.of(new VoucherLineInput("1001", new BigDecimal("88.00"), null, null),
                                new VoucherLineInput("6001", null, new BigDecimal("88.00"), null)),
                        OPERATOR));
        txTemplate.executeWithoutResult(s -> voucherService.post(vch, OPERATOR));

        // 关账
        txTemplate.executeWithoutResult(s -> periodService.close(period, OPERATOR));
        assertThat(periodService.isOpen(period)).isFalse();

        // 关账期冲销 → PeriodClosedException 且整事务回滚
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> voucherAppService.reverse(vch, OPERATOR)))
                .isInstanceOf(PeriodClosedException.class);

        // 回滚验证：无红字残留（以本凭证为来源的红字数为 0）
        Long redCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher WHERE tenant_id = 0 "
                        + "AND source_doc_type = 'VOUCHER_REVERSAL' AND source_doc_no = ?",
                Long.class, vch);
        assertThat(redCount).as("关账期冲销被拒，无红字残留").isZero();
        // 原凭证仍 APPROVED、未被冲销
        Map<String, Object> origRow = jdbc.queryForMap(
                "SELECT status, reversed_by_id FROM voucher WHERE tenant_id = 0 AND doc_no = ?", vch);
        assertThat(origRow.get("status")).as("原凭证仍已过账").isEqualTo("APPROVED");
        assertThat(origRow.get("reversed_by_id")).as("原凭证未回填红字号").isNull();
    }

    // ----------------------------------------------------- 前置状态：非 APPROVED（草稿）凭证冲销被拒

    @Test
    void 冲销草稿凭证_被拒IllegalState() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String period = "202609";
        LocalDate voucherDate = LocalDate.of(2026, 9, 10);
        String vch = "VCH-DRAFT-" + suffix;

        txTemplate.executeWithoutResult(s -> periodService.open(period, OPERATOR));
        // 仅建单，不过账（DRAFT）
        txTemplate.executeWithoutResult(s ->
                voucherService.create(vch, period, voucherDate, "草稿凭证",
                        List.of(new VoucherLineInput("1001", new BigDecimal("10.00"), null, null),
                                new VoucherLineInput("6001", null, new BigDecimal("10.00"), null)),
                        OPERATOR));

        // 草稿凭证冲销 → IllegalState（仅 APPROVED 可冲销）
        assertThatThrownBy(() ->
                txTemplate.executeWithoutResult(s -> voucherAppService.reverse(vch, OPERATOR)))
                .isInstanceOf(IllegalStateException.class);

        // 草稿仍 DRAFT、无红字
        assertThat(jdbc.queryForObject("SELECT status FROM voucher WHERE tenant_id = 0 AND doc_no = ?",
                String.class, vch)).isEqualTo("DRAFT");
        Long redCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher WHERE tenant_id = 0 "
                        + "AND source_doc_type = 'VOUCHER_REVERSAL' AND source_doc_no = ?",
                Long.class, vch);
        assertThat(redCount).isZero();
    }

    /** trialBalance 派生余额借/贷任一方可能为 null（无该方向发生额），统一归零便于做算术。 */
    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
