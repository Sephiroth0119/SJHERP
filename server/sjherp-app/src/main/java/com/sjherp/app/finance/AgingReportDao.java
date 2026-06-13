package com.sjherp.app.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;

/**
 * 应收应付账龄分析只读 DAO（M4-T03，<b>只读</b>，参照 {@code ReportQueryDao}）。
 *
 * <p>CLAUDE.md 铁律「写操作只能经领域服务，报表/校验只读除外」——本类只有 SELECT/聚合，
 * 零 INSERT/UPDATE/DELETE。金额一律 DECIMAL（不经 float/double），跨行合计用 DECIMAL 精确 SQL 聚合。
 *
 * <p><b>主表一律 LEFT JOIN customer/supplier</b>：账龄是财务产出，绝不能因某对手方档案缺失（理论上
 * 不应发生）而<b>静默丢行</b>——宁可显示 null 名称也要把这一行暴露出来（财务报表红线）。LEFT JOIN
 * 目标为主键，绝无行膨胀。tenant_id 恒 0（ADR-002）。
 *
 * <h2>账龄口径（设计真源 §4）</h2>
 * <ul>
 *   <li>仅取<b>未结清</b>记录：{@code status <> 'SETTLED'} 与 {@code amount - settled_amount > 0} 双口径并用
 *       （领域服务保证二者等价，余额为兜底单一真相，防越权直插的状态-余额不一致噪声行）；</li>
 *   <li>分桶基于<b>未核销余额</b> {@code amount - settled_amount}（非原始金额）；</li>
 *   <li>逾期天数 = {@code asOf - 到期日}（AR 到期日可空 → {@code COALESCE(due_date, DATE(created_at))}；
 *       AP {@code due_date} 非空）；</li>
 *   <li>桶：未到期（逾期 ≤ 0）/ 逾期 1-30 / 31-60 / 61-90 / 90+；用 {@code CASE} 按桶 {@code SUM}；</li>
 *   <li>按对手方汇总（每行 = counterparty + 5 桶金额 + 该方未核销合计）+ 全过滤集总计行（grandTotal）。</li>
 * </ul>
 */
@Repository
public class AgingReportDao {

    /** 每页条数上限（防一次拉全表，口径同 {@code ReportQueryDao}）。 */
    public static final int MAX_SIZE = 200;

    // =====================================================================
    // 账龄行 / 合计 / 报表（应收应付共用结构，counterpartyId/Code/Name 语义随类型而异）
    // =====================================================================

    /**
     * 账龄汇总行（按对手方）：5 个逾期桶的未核销金额 + 该对手方未核销合计。
     * counterparty = 客户（应收）或供应商（应付）；code/name 在档案缺失时为 null（LEFT JOIN 不丢行）。
     */
    public record AgingRow(long counterpartyId, String counterpartyCode, String counterpartyName,
                           BigDecimal notDue, BigDecimal overdue1To30, BigDecimal overdue31To60,
                           BigDecimal overdue61To90, BigDecimal overdue90Plus,
                           BigDecimal totalOutstanding) {
    }

    /** 全过滤集总计行（各桶 grandTotal + 总未核销）。 */
    public record AgingGrandTotal(BigDecimal notDue, BigDecimal overdue1To30, BigDecimal overdue31To60,
                                  BigDecimal overdue61To90, BigDecimal overdue90Plus,
                                  BigDecimal totalOutstanding) {
    }

    /** 账龄报表：截止日 + 分页明细 + 全过滤集总计。 */
    public record AgingReport(LocalDate asOf, PageResult<AgingRow> page, AgingGrandTotal grandTotal) {
    }

    private static final RowMapper<AgingRow> AGING_MAPPER = (rs, n) -> new AgingRow(
            rs.getLong("counterparty_id"), rs.getString("counterparty_code"),
            rs.getString("counterparty_name"),
            nz(rs.getBigDecimal("not_due")), nz(rs.getBigDecimal("overdue_1_30")),
            nz(rs.getBigDecimal("overdue_31_60")), nz(rs.getBigDecimal("overdue_61_90")),
            nz(rs.getBigDecimal("overdue_90_plus")), nz(rs.getBigDecimal("total_outstanding")));

    private final JdbcTemplate jdbc;

    public AgingReportDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    // ---------------------------------------------------------------
    // 应收账龄（按客户）
    // ---------------------------------------------------------------

    /**
     * 应收账龄：asOf 截止日（必填，controller 缺省今天）；customerId 可选过滤；按客户汇总分页。
     * 逾期天数 = asOf − COALESCE(due_date, DATE(created_at))；仅未结清记录；分桶基于未核销余额。
     */
    @Transactional(readOnly = true)
    public AgingReport receivableAging(LocalDate asOf, Long customerId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        // 逾期天数表达式：asOf − 到期日（AR 到期日可空 → 退化到创建日）
        String overdueDays = "DATEDIFF(?, COALESCE(ar.due_date, DATE(ar.created_at)))";
        String outstanding = "(ar.amount - ar.settled_amount)";

        // 双口径筛选（设计真源 §4 + 评审加固）：status<>'SETTLED' 与「未核销余额>0」并用——
        // 领域服务保证两者等价，但越过领域的直插/异常数据若致状态-余额不一致（如零额 OPEN），
        // 以「余额>0」为单一真相兜底，使账龄筛选基准与分桶基准（amount-settled）解耦一致、不出噪声行。
        StringBuilder where = new StringBuilder(
                " WHERE ar.tenant_id = 0 AND ar.status <> 'SETTLED' AND (ar.amount - ar.settled_amount) > 0");
        List<Object> filterArgs = new ArrayList<>();
        if (customerId != null) {
            where.append(" AND ar.customer_id = ?");
            filterArgs.add(customerId);
        }

        String from = " FROM accounts_receivable ar"
                + " LEFT JOIN customer c ON c.id = ar.customer_id" + where;

        return buildAgingReport(asOf, "ar.customer_id", "c.code", "c.name",
                overdueDays, outstanding, from, filterArgs, safePage, safeSize);
    }

    // ---------------------------------------------------------------
    // 应付账龄（按供应商）
    // ---------------------------------------------------------------

    /**
     * 应付账龄：asOf 截止日（必填，controller 缺省今天）；supplierId 可选过滤；按供应商汇总分页。
     * 逾期天数 = asOf − due_date（AP 到期日非空）；仅未结清记录；分桶基于未核销余额。
     */
    @Transactional(readOnly = true)
    public AgingReport payableAging(LocalDate asOf, Long supplierId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);

        String overdueDays = "DATEDIFF(?, ap.due_date)";
        String outstanding = "(ap.amount - ap.settled_amount)";

        // 同应收：status<>'SETTLED' 与「未核销余额>0」双口径筛选（评审加固，余额为单一真相兜底）
        StringBuilder where = new StringBuilder(
                " WHERE ap.tenant_id = 0 AND ap.status <> 'SETTLED' AND (ap.amount - ap.settled_amount) > 0");
        List<Object> filterArgs = new ArrayList<>();
        if (supplierId != null) {
            where.append(" AND ap.supplier_id = ?");
            filterArgs.add(supplierId);
        }

        String from = " FROM accounts_payable ap"
                + " LEFT JOIN supplier s ON s.id = ap.supplier_id" + where;

        return buildAgingReport(asOf, "ap.supplier_id", "s.code", "s.name",
                overdueDays, outstanding, from, filterArgs, safePage, safeSize);
    }

    // ---------------------------------------------------------------
    // 共用：组装分桶 SQL + 分页 + grandTotal（应收应付仅 from/列名/逾期表达式不同）
    // ---------------------------------------------------------------

    /**
     * 组装账龄报表：内层按对手方 GROUP BY，CASE 切 5 桶 SUM 未核销余额；外层取计数/总计/分页。
     *
     * @param overdueDaysExpr 逾期天数 SQL（含 1 个 {@code ?} 占位 asOf）
     * @param outstandingExpr 未核销余额 SQL
     * @param fromWithWhere   FROM + LEFT JOIN + WHERE（含过滤条件占位）
     * @param filterArgs      WHERE 过滤参数（不含 asOf；asOf 在 SELECT 的 5 个桶 CASE 内各出现一次）
     */
    private AgingReport buildAgingReport(LocalDate asOf, String cpIdCol, String cpCodeCol,
                                         String cpNameCol, String overdueDaysExpr,
                                         String outstandingExpr, String fromWithWhere,
                                         List<Object> filterArgs, int page, int size) {
        // 5 个桶各引用一次逾期天数表达式 → asOf 在分桶聚合 SELECT 内出现 5 次，须排在 WHERE 过滤参数之前
        String bucketSelect = ""
                + " SUM(CASE WHEN " + overdueDaysExpr + " <= 0 THEN " + outstandingExpr + " ELSE 0 END) AS not_due,"
                + " SUM(CASE WHEN " + overdueDaysExpr + " BETWEEN 1 AND 30 THEN " + outstandingExpr + " ELSE 0 END) AS overdue_1_30,"
                + " SUM(CASE WHEN " + overdueDaysExpr + " BETWEEN 31 AND 60 THEN " + outstandingExpr + " ELSE 0 END) AS overdue_31_60,"
                + " SUM(CASE WHEN " + overdueDaysExpr + " BETWEEN 61 AND 90 THEN " + outstandingExpr + " ELSE 0 END) AS overdue_61_90,"
                + " SUM(CASE WHEN " + overdueDaysExpr + " > 90 THEN " + outstandingExpr + " ELSE 0 END) AS overdue_90_plus,"
                + " SUM(" + outstandingExpr + ") AS total_outstanding";

        // 聚合 SELECT 内的 5 个 asOf（每桶 CASE 一个），随后才是 WHERE 过滤参数
        List<Object> aggArgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            aggArgs.add(asOf);
        }
        aggArgs.addAll(filterArgs);

        String innerGroup = "SELECT " + cpIdCol + " AS counterparty_id,"
                + " MAX(" + cpCodeCol + ") AS counterparty_code,"
                + " MAX(" + cpNameCol + ") AS counterparty_name,"
                + bucketSelect
                + fromWithWhere
                + " GROUP BY " + cpIdCol;

        // 总行数 = 不同对手方个数
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + innerGroup + ") g",
                Long.class, aggArgs.toArray());
        long totalCount = total == null ? 0 : total;

        // grandTotal：对全过滤集（未分组）按相同桶规则一次性 SUM（口径与逐行 Σ 一致）
        AgingGrandTotal grandTotal = jdbc.queryForObject(
                "SELECT" + bucketSelect + fromWithWhere,
                (rs, n) -> new AgingGrandTotal(
                        nz(rs.getBigDecimal("not_due")), nz(rs.getBigDecimal("overdue_1_30")),
                        nz(rs.getBigDecimal("overdue_31_60")), nz(rs.getBigDecimal("overdue_61_90")),
                        nz(rs.getBigDecimal("overdue_90_plus")), nz(rs.getBigDecimal("total_outstanding"))),
                aggArgs.toArray());

        if (totalCount == 0) {
            return new AgingReport(asOf, new PageResult<>(List.of(), 0, page, size),
                    grandTotal == null ? zeroGrandTotal() : grandTotal);
        }

        List<Object> pageArgs = new ArrayList<>(aggArgs);
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        List<AgingRow> rows = jdbc.query(
                innerGroup + " ORDER BY counterparty_id LIMIT ? OFFSET ?",
                AGING_MAPPER, pageArgs.toArray());

        return new AgingReport(asOf, new PageResult<>(rows, totalCount, page, size),
                grandTotal == null ? zeroGrandTotal() : grandTotal);
    }

    private static AgingGrandTotal zeroGrandTotal() {
        return new AgingGrandTotal(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** SUM/聚合列可能为 NULL（空集），统一收敛为 0。 */
    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
