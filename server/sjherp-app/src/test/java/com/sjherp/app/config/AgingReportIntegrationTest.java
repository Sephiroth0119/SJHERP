package com.sjherp.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sjherp.app.finance.AgingReportDao;
import com.sjherp.app.finance.AgingReportDao.AgingGrandTotal;
import com.sjherp.app.finance.AgingReportDao.AgingReport;
import com.sjherp.app.finance.AgingReportDao.AgingRow;

/**
 * 应收应付账龄分析真库集成测试（M4-T03 验收②，Testcontainers 真实 MySQL）。
 *
 * <p>账龄分桶口径含 MySQL {@code DATEDIFF}（应收 {@code COALESCE(due_date, DATE(created_at))}），
 * 不可 H2 跑——必须真库（参照 {@link ReportQueryIntegrationTest}）。本测试只验账龄 DAO，无需领域服务
 * 装配链，直插应收/应付夹具（设计真源 §7「经发票过账<b>或直插测试夹具</b>」），对每个逾期桶/部分核销/
 * 全结清/对手方档案缺失各精确造样本，再断言：
 * <ul>
 *   <li><b>tie-out</b>：每行 Σ5 桶 = 该行未核销合计；grandTotal 各桶 = Σ各行对应桶；
 *       grandTotal.totalOutstanding = Σ(amount−settled_amount) where status&lt;&gt;'SETTLED'；</li>
 *   <li>全结清记录（SETTLED）不出现在任何账龄行；</li>
 *   <li>对手方档案缺失（customer/supplier 无对应行）时 LEFT JOIN 仍出行（counterparty code/name 为 null），
 *       不静默丢行（财务报表红线）。</li>
 * </ul>
 *
 * <p>每个 case 用独立的对手方 id 段（基于运行时 nextId）隔离，避免共享容器内跨用例污染：
 * 账龄过滤到本 case 的 customerId/supplierId，断言只覆盖本 case 样本。
 *
 * <p>默认不执行：@Tag("integration-db") 被父 POM excludedGroups 排除（本机可能无 Docker）。
 * CI 的 backend-integration-db job 显式运行。
 */
@Tag("integration-db")
class AgingReportIntegrationTest {

    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    private static final String OPERATOR = "it-aging";

    private static final AtomicLong ID_GEN = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    /** 账龄截止日：所有样本到期日/创建日相对它布点。 */
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AgingReportDao agingReportDao;

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
        agingReportDao = context.getBean(AgingReportDao.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
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

        // 账龄只读 DAO（生产由 @Repository 组件扫描；此隔离上下文显式 new）
        @Bean
        AgingReportDao agingReportDao(JdbcTemplate jdbcTemplate) {
            return new AgingReportDao(jdbcTemplate);
        }
    }

    private static long nextId() {
        return ID_GEN.incrementAndGet();
    }

    // =================================================================================
    // 应收账龄：5 桶各一户 + 部分核销 + 全结清不出现 + 客户档案缺失 LEFT JOIN 仍出行
    // =================================================================================

    @Test
    void 应收账龄_五桶分布_部分核销_全结清剔除_档案缺失不丢行_tieout() {
        // 各对手方独立 id，便于过滤到本 case
        long custNotDue = nextId();        // 未到期（due_date 在 asOf 之后）
        long cust1To30 = nextId();         // 逾期 1-30
        long cust31To60 = nextId();        // 逾期 31-60
        long cust61To90 = nextId();        // 逾期 61-90
        long cust90Plus = nextId();        // 逾期 90+
        long custPartial = nextId();       // 逾期 1-30 + 部分核销（未核销余额入桶）
        long custSettled = nextId();       // 全结清（不应出现在账龄）
        long custDueNull = nextId();       // due_date 为空 → 退化到 created_at；档案缺失（LEFT JOIN）

        // 建客户档案（custDueNull 故意不建，验证 LEFT JOIN 不丢行）
        insertCustomer(custNotDue, "CUS-ND");
        insertCustomer(cust1To30, "CUS-30");
        insertCustomer(cust31To60, "CUS-60");
        insertCustomer(cust61To90, "CUS-90");
        insertCustomer(cust90Plus, "CUS-90P");
        insertCustomer(custPartial, "CUS-PART");
        insertCustomer(custSettled, "CUS-SET");
        // custDueNull：不建客户档案

        String suffix = Long.toString(System.nanoTime(), 36);
        // 未到期：due = asOf + 10 → DATEDIFF = -10 ≤ 0 → not_due，1000.00
        insertReceivable(custNotDue, "1000.00", "0.00", "OPEN", AS_OF.plusDays(10), AS_OF,
                "SINV-ND-" + suffix);
        // 逾期 1-30：due = asOf - 10 → DATEDIFF = 10 → overdue_1_30，2000.00
        insertReceivable(cust1To30, "2000.00", "0.00", "OPEN", AS_OF.minusDays(10), AS_OF,
                "SINV-30-" + suffix);
        // 逾期 31-60：due = asOf - 45 → 45，3000.00
        insertReceivable(cust31To60, "3000.00", "0.00", "OPEN", AS_OF.minusDays(45), AS_OF,
                "SINV-60-" + suffix);
        // 逾期 61-90：due = asOf - 75 → 75，4000.00
        insertReceivable(cust61To90, "4000.00", "0.00", "OPEN", AS_OF.minusDays(75), AS_OF,
                "SINV-90-" + suffix);
        // 逾期 90+：due = asOf - 120 → 120，5000.00
        insertReceivable(cust90Plus, "5000.00", "0.00", "OPEN", AS_OF.minusDays(120), AS_OF,
                "SINV-90P-" + suffix);
        // 部分核销：amount 1000 / 已核销 300 → 未核销 700 入 overdue_1_30（due = asOf - 5）
        insertReceivable(custPartial, "1000.00", "300.00", "PARTIAL", AS_OF.minusDays(5), AS_OF,
                "SINV-PART-" + suffix);
        // 全结清：amount 8888 / 已核销 8888 / SETTLED → 不应出现在任何账龄行
        insertReceivable(custSettled, "8888.00", "8888.00", "SETTLED", AS_OF.minusDays(20), AS_OF,
                "SINV-SET-" + suffix);
        // due_date 为空 + 档案缺失：DATEDIFF(asOf, DATE(created_at))；created_at = asOf - 100 → 100 → overdue_90_plus，600.00
        insertReceivableDueNull(custDueNull, "600.00", "0.00", "OPEN",
                AS_OF.minusDays(100), "SINV-NULL-" + suffix);

        // 拉本 case 全部 8 户（一次足够：本 case id 段隔离，分页 size 设大）
        AgingReport report = agingReportDao.receivableAging(AS_OF, null, 1, 200);

        // 仅取本 case 的对手方行（容器共享，过滤掉其他用例样本）
        List<Long> caseIds = List.of(custNotDue, cust1To30, cust31To60, cust61To90, cust90Plus,
                custPartial, custDueNull);
        List<AgingRow> caseRows = report.page().items().stream()
                .filter(r -> caseIds.contains(r.counterpartyId()))
                .toList();

        // 7 户出行（全结清那户不出现）；id 不含 custSettled
        assertThat(caseRows).as("除全结清外 7 户应出现在账龄").hasSize(7);
        assertThat(caseRows).extracting(AgingRow::counterpartyId)
                .doesNotContain(custSettled);

        // 逐户落桶校验 + 每行 tie-out（Σ5 桶 = 该行未核销合计）
        assertRow(findRow(caseRows, custNotDue), "1000.00", "0", "0", "0", "0", "1000.00");
        assertRow(findRow(caseRows, cust1To30), "0", "2000.00", "0", "0", "0", "2000.00");
        assertRow(findRow(caseRows, cust31To60), "0", "0", "3000.00", "0", "0", "3000.00");
        assertRow(findRow(caseRows, cust61To90), "0", "0", "0", "4000.00", "0", "4000.00");
        assertRow(findRow(caseRows, cust90Plus), "0", "0", "0", "0", "5000.00", "5000.00");
        assertRow(findRow(caseRows, custPartial), "0", "700.00", "0", "0", "0", "700.00");
        // due_date 为空 + 档案缺失：100 天 → overdue_90_plus；档案缺失 LEFT JOIN 仍出行（code/name 为 null）
        AgingRow nullRow = findRow(caseRows, custDueNull);
        assertRow(nullRow, "0", "0", "0", "0", "600.00", "600.00");
        assertThat(nullRow.counterpartyCode()).as("客户档案缺失 → code 为 null（LEFT JOIN 不丢行）").isNull();
        assertThat(nullRow.counterpartyName()).as("客户档案缺失 → name 为 null").isNull();

        // 每行 tie-out：Σ5 桶 == 该行 totalOutstanding
        for (AgingRow r : caseRows) {
            assertThat(sumBuckets(r))
                    .as("应收行 %d Σ5桶 = 该行未核销合计", r.counterpartyId())
                    .isEqualByComparingTo(r.totalOutstanding());
        }

        // ---- grandTotal tie-out（用 customerId 过滤逐户拉取，逐桶 Σ 后与全集 grandTotal 对比，
        //      避免共享容器内其他用例污染 grandTotal） ----
        BigDecimal expNotDue = BigDecimal.ZERO, exp1To30 = BigDecimal.ZERO, exp31To60 = BigDecimal.ZERO,
                exp61To90 = BigDecimal.ZERO, exp90Plus = BigDecimal.ZERO, expTotal = BigDecimal.ZERO;
        for (long id : caseIds) {
            AgingReport one = agingReportDao.receivableAging(AS_OF, id, 1, 20);
            AgingGrandTotal gt = one.grandTotal();
            expNotDue = expNotDue.add(gt.notDue());
            exp1To30 = exp1To30.add(gt.overdue1To30());
            exp31To60 = exp31To60.add(gt.overdue31To60());
            exp61To90 = exp61To90.add(gt.overdue61To90());
            exp90Plus = exp90Plus.add(gt.overdue90Plus());
            expTotal = expTotal.add(gt.totalOutstanding());
        }
        // 本 case 桶分布期望：not_due=1000；1-30=2000+700=2700；31-60=3000；61-90=4000；90+=5000+600=5600
        assertThat(expNotDue).as("本 case Σ not_due").isEqualByComparingTo("1000.00");
        assertThat(exp1To30).as("本 case Σ overdue_1_30").isEqualByComparingTo("2700.00");
        assertThat(exp31To60).as("本 case Σ overdue_31_60").isEqualByComparingTo("3000.00");
        assertThat(exp61To90).as("本 case Σ overdue_61_90").isEqualByComparingTo("4000.00");
        assertThat(exp90Plus).as("本 case Σ overdue_90_plus").isEqualByComparingTo("5600.00");
        // 总未核销 = 1000+2700+3000+4000+5600 = 16300（全结清 8888 不计入）
        assertThat(expTotal).as("本 case Σ 总未核销（全结清剔除）").isEqualByComparingTo("16300.00");

        // grandTotal 总未核销口径旁证：单户过滤 grandTotal.totalOutstanding == 该户 (amount-settled) where 未结清
        AgingReport partialOnly = agingReportDao.receivableAging(AS_OF, custPartial, 1, 20);
        assertThat(partialOnly.grandTotal().totalOutstanding())
                .as("部分核销户 grandTotal 总未核销 = amount - settled").isEqualByComparingTo("700.00");
        // 全结清户单过滤：无行、grandTotal 全 0（status='SETTLED' 被 WHERE 排除）
        AgingReport settledOnly = agingReportDao.receivableAging(AS_OF, custSettled, 1, 20);
        assertThat(settledOnly.page().items()).as("全结清户不出账龄行").isEmpty();
        assertThat(settledOnly.grandTotal().totalOutstanding())
                .as("全结清户 grandTotal 未核销为 0").isEqualByComparingTo("0");
    }

    // =================================================================================
    // 桶边界：DATEDIFF 精确临界（0/1/30/31/90/91）逐点钉住落桶，防 <=0 / BETWEEN 端点被误改
    // =================================================================================

    @Test
    void 应收账龄_逾期天数桶边界_精确临界落桶() {
        long custEdge = nextId();
        insertCustomer(custEdge, "CUS-EDGE");
        String suffix = Long.toString(System.nanoTime(), 36);
        // 单客户 6 笔，逾期天数恰落各桶临界（due = AS_OF - d）；每笔 100.00。
        // d=0 → 未到期（<=0 边界）；d=1 → 1-30 下界；d=30 → 1-30 上界；
        // d=31 → 31-60 下界；d=90 → 61-90 上界；d=91 → 90+ 下界。
        insertReceivable(custEdge, "100.00", "0.00", "OPEN", AS_OF, AS_OF, "SINV-E0-" + suffix);
        insertReceivable(custEdge, "100.00", "0.00", "OPEN", AS_OF.minusDays(1), AS_OF, "SINV-E1-" + suffix);
        insertReceivable(custEdge, "100.00", "0.00", "OPEN", AS_OF.minusDays(30), AS_OF, "SINV-E30-" + suffix);
        insertReceivable(custEdge, "100.00", "0.00", "OPEN", AS_OF.minusDays(31), AS_OF, "SINV-E31-" + suffix);
        insertReceivable(custEdge, "100.00", "0.00", "OPEN", AS_OF.minusDays(90), AS_OF, "SINV-E90-" + suffix);
        insertReceivable(custEdge, "100.00", "0.00", "OPEN", AS_OF.minusDays(91), AS_OF, "SINV-E91-" + suffix);

        AgingReport report = agingReportDao.receivableAging(AS_OF, custEdge, 1, 20);
        assertThat(report.page().items()).as("单客户应聚合为 1 行").hasSize(1);
        AgingRow row = report.page().items().get(0);
        // not_due=100(d0)；1-30=200(d1+d30)；31-60=100(d31)；61-90=100(d90)；90+=100(d91)；合计 600
        assertRow(row, "100.00", "200.00", "100.00", "100.00", "100.00", "600.00");
    }

    // =================================================================================
    // 应付账龄：到期日非空，5 桶 + 部分核销 + 全结清剔除 + 供应商档案缺失 LEFT JOIN 仍出行
    // =================================================================================

    @Test
    void 应付账龄_五桶分布_部分核销_全结清剔除_档案缺失不丢行_tieout() {
        long supNotDue = nextId();
        long sup1To30 = nextId();
        long sup31To60 = nextId();
        long sup61To90 = nextId();
        long sup90Plus = nextId();
        long supPartial = nextId();
        long supSettled = nextId();
        long supNoMaster = nextId();   // 档案缺失（LEFT JOIN）

        insertSupplier(supNotDue, "SUP-ND");
        insertSupplier(sup1To30, "SUP-30");
        insertSupplier(sup31To60, "SUP-60");
        insertSupplier(sup61To90, "SUP-90");
        insertSupplier(sup90Plus, "SUP-90P");
        insertSupplier(supPartial, "SUP-PART");
        insertSupplier(supSettled, "SUP-SET");
        // supNoMaster：不建供应商档案

        String suffix = Long.toString(System.nanoTime(), 36);
        insertPayable(supNotDue, "1000.00", "0.00", "OPEN", AS_OF.plusDays(10), "PINV-ND-" + suffix);
        insertPayable(sup1To30, "2000.00", "0.00", "OPEN", AS_OF.minusDays(10), "PINV-30-" + suffix);
        insertPayable(sup31To60, "3000.00", "0.00", "OPEN", AS_OF.minusDays(45), "PINV-60-" + suffix);
        insertPayable(sup61To90, "4000.00", "0.00", "OPEN", AS_OF.minusDays(75), "PINV-90-" + suffix);
        insertPayable(sup90Plus, "5000.00", "0.00", "OPEN", AS_OF.minusDays(120), "PINV-90P-" + suffix);
        insertPayable(supPartial, "1000.00", "300.00", "PARTIAL", AS_OF.minusDays(5), "PINV-PART-" + suffix);
        insertPayable(supSettled, "8888.00", "8888.00", "SETTLED", AS_OF.minusDays(20), "PINV-SET-" + suffix);
        // 档案缺失：due = asOf - 100 → overdue_90_plus，600.00
        insertPayable(supNoMaster, "600.00", "0.00", "OPEN", AS_OF.minusDays(100), "PINV-NOM-" + suffix);

        AgingReport report = agingReportDao.payableAging(AS_OF, null, 1, 200);

        List<Long> caseIds = List.of(supNotDue, sup1To30, sup31To60, sup61To90, sup90Plus,
                supPartial, supNoMaster);
        List<AgingRow> caseRows = report.page().items().stream()
                .filter(r -> caseIds.contains(r.counterpartyId()))
                .toList();

        assertThat(caseRows).as("除全结清外 7 户应出现在应付账龄").hasSize(7);
        assertThat(caseRows).extracting(AgingRow::counterpartyId).doesNotContain(supSettled);

        assertRow(findRow(caseRows, supNotDue), "1000.00", "0", "0", "0", "0", "1000.00");
        assertRow(findRow(caseRows, sup1To30), "0", "2000.00", "0", "0", "0", "2000.00");
        assertRow(findRow(caseRows, sup31To60), "0", "0", "3000.00", "0", "0", "3000.00");
        assertRow(findRow(caseRows, sup61To90), "0", "0", "0", "4000.00", "0", "4000.00");
        assertRow(findRow(caseRows, sup90Plus), "0", "0", "0", "0", "5000.00", "5000.00");
        assertRow(findRow(caseRows, supPartial), "0", "700.00", "0", "0", "0", "700.00");
        AgingRow noMasterRow = findRow(caseRows, supNoMaster);
        assertRow(noMasterRow, "0", "0", "0", "0", "600.00", "600.00");
        assertThat(noMasterRow.counterpartyCode()).as("供应商档案缺失 → code 为 null（LEFT JOIN 不丢行）").isNull();
        assertThat(noMasterRow.counterpartyName()).as("供应商档案缺失 → name 为 null").isNull();

        for (AgingRow r : caseRows) {
            assertThat(sumBuckets(r))
                    .as("应付行 %d Σ5桶 = 该行未核销合计", r.counterpartyId())
                    .isEqualByComparingTo(r.totalOutstanding());
        }

        // grandTotal tie-out（逐户过滤求和）
        BigDecimal expNotDue = BigDecimal.ZERO, exp1To30 = BigDecimal.ZERO, exp31To60 = BigDecimal.ZERO,
                exp61To90 = BigDecimal.ZERO, exp90Plus = BigDecimal.ZERO, expTotal = BigDecimal.ZERO;
        for (long id : caseIds) {
            AgingGrandTotal gt = agingReportDao.payableAging(AS_OF, id, 1, 20).grandTotal();
            expNotDue = expNotDue.add(gt.notDue());
            exp1To30 = exp1To30.add(gt.overdue1To30());
            exp31To60 = exp31To60.add(gt.overdue31To60());
            exp61To90 = exp61To90.add(gt.overdue61To90());
            exp90Plus = exp90Plus.add(gt.overdue90Plus());
            expTotal = expTotal.add(gt.totalOutstanding());
        }
        assertThat(expNotDue).isEqualByComparingTo("1000.00");
        assertThat(exp1To30).isEqualByComparingTo("2700.00");
        assertThat(exp31To60).isEqualByComparingTo("3000.00");
        assertThat(exp61To90).isEqualByComparingTo("4000.00");
        assertThat(exp90Plus).isEqualByComparingTo("5600.00");
        assertThat(expTotal).as("应付本 case Σ 总未核销（全结清剔除）").isEqualByComparingTo("16300.00");

        // 全结清户单过滤：无行、grandTotal 未核销 0
        AgingReport settledOnly = agingReportDao.payableAging(AS_OF, supSettled, 1, 20);
        assertThat(settledOnly.page().items()).as("全结清供应商不出账龄行").isEmpty();
        assertThat(settledOnly.grandTotal().totalOutstanding()).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------
    // 断言工具
    // ---------------------------------------------------------------

    /** 5 桶 + 未核销合计逐项断言（字符串以 BigDecimal 比较，容忍标度）。 */
    private static void assertRow(AgingRow r, String notDue, String b1, String b2, String b3, String b4,
                                  String total) {
        assertThat(r.notDue()).as("行 %d not_due", r.counterpartyId()).isEqualByComparingTo(notDue);
        assertThat(r.overdue1To30()).as("行 %d overdue_1_30", r.counterpartyId()).isEqualByComparingTo(b1);
        assertThat(r.overdue31To60()).as("行 %d overdue_31_60", r.counterpartyId()).isEqualByComparingTo(b2);
        assertThat(r.overdue61To90()).as("行 %d overdue_61_90", r.counterpartyId()).isEqualByComparingTo(b3);
        assertThat(r.overdue90Plus()).as("行 %d overdue_90_plus", r.counterpartyId()).isEqualByComparingTo(b4);
        assertThat(r.totalOutstanding()).as("行 %d total_outstanding", r.counterpartyId())
                .isEqualByComparingTo(total);
    }

    private static BigDecimal sumBuckets(AgingRow r) {
        return r.notDue().add(r.overdue1To30()).add(r.overdue31To60())
                .add(r.overdue61To90()).add(r.overdue90Plus());
    }

    private static AgingRow findRow(List<AgingRow> rows, long counterpartyId) {
        return rows.stream().filter(r -> r.counterpartyId() == counterpartyId).findFirst()
                .orElseThrow(() -> new AssertionError("账龄缺失对手方行: " + counterpartyId));
    }

    // ---------------------------------------------------------------
    // 直插夹具（DAO 只读，不经领域服务；造档案/子账行精确控制日期与核销态）
    // ---------------------------------------------------------------

    private void insertCustomer(long id, String code) {
        jdbc.update("INSERT INTO customer (id, tenant_id, code, name, settlement_method, currency, "
                        + "status, created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, 0, ?, ?, 'MONTHLY', 'CNY', 'ENABLED', ?, NOW(6), ?, NOW(6))",
                id, code + "-" + id, "客户" + code, OPERATOR, OPERATOR);
    }

    private void insertSupplier(long id, String code) {
        jdbc.update("INSERT INTO supplier (id, tenant_id, code, name, settlement_method, "
                        + "status, created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, 0, ?, ?, 'MONTHLY', 'ENABLED', ?, NOW(6), ?, NOW(6))",
                id, code + "-" + id, "供应商" + code, OPERATOR, OPERATOR);
    }

    private void insertReceivable(long customerId, String amount, String settled, String status,
                                  LocalDate dueDate, LocalDate createdDate, String sourceDocNo) {
        jdbc.update("INSERT INTO accounts_receivable (tenant_id, customer_id, amount, settled_amount, "
                        + "source_doc_no, due_date, status, created_by, created_at) "
                        + "VALUES (0, ?, ?, ?, ?, ?, ?, ?, ?)",
                customerId, new BigDecimal(amount), new BigDecimal(settled), sourceDocNo,
                java.sql.Date.valueOf(dueDate), status, OPERATOR,
                java.sql.Timestamp.valueOf(createdDate.atStartOfDay()));
    }

    /** 应收 due_date 置空（验证 COALESCE(due_date, DATE(created_at)) 退化口径）。 */
    private void insertReceivableDueNull(long customerId, String amount, String settled, String status,
                                         LocalDate createdDate, String sourceDocNo) {
        jdbc.update("INSERT INTO accounts_receivable (tenant_id, customer_id, amount, settled_amount, "
                        + "source_doc_no, due_date, status, created_by, created_at) "
                        + "VALUES (0, ?, ?, ?, ?, NULL, ?, ?, ?)",
                customerId, new BigDecimal(amount), new BigDecimal(settled), sourceDocNo,
                status, OPERATOR, java.sql.Timestamp.valueOf(createdDate.atStartOfDay()));
    }

    private void insertPayable(long supplierId, String amount, String settled, String status,
                               LocalDate dueDate, String sourceDocNo) {
        jdbc.update("INSERT INTO accounts_payable (tenant_id, supplier_id, amount, source_doc_no, "
                        + "due_date, status, settled_amount, created_by, created_at) "
                        + "VALUES (0, ?, ?, ?, ?, ?, ?, ?, NOW(6))",
                supplierId, new BigDecimal(amount), sourceDocNo, java.sql.Date.valueOf(dueDate),
                status, new BigDecimal(settled), OPERATOR);
    }
}
