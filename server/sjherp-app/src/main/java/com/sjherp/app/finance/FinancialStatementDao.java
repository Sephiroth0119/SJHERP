package com.sjherp.app.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 财务报表（资产负债表 + 利润表）只读派生 DAO（M4-T06，<b>只读</b>，参照 {@link AgingReportDao}）。
 *
 * <p>CLAUDE.md 铁律「写操作只能经领域服务，报表/校验只读除外」——本类只有 SELECT/聚合，
 * 零 INSERT/UPDATE/DELETE。两报表均由 {@code voucher/voucher_line} 累计派生（设计真源 §0：
 * 不建 account_period_balance 冻结表，已关账期禁止过账故派生余额天然稳定）。
 *
 * <p>精度（CLAUDE.md 原则 5）：金额一律 DECIMAL，跨行合计用 DECIMAL 精确 SQL 聚合，
 * {@code nz()} 收敛 NULL→0，绝不经 float/double。{@code period} 为定长 yyyyMM（6 位）字符串，
 * 字典序等同时序，故区间用字符串 {@code <=} / {@code BETWEEN} 比较。tenant_id 恒 0（ADR-002）。
 *
 * <p>本 DAO 仅产出"科目 → 借贷累计净额"原料行（{@link AccountNetRow}），不做任何报表行归集/
 * 平衡判断——映射、分类、平衡校验均在 {@link FinancialStatementService} 用 {@code AccountService}
 * 元数据完成（设计真源 §3），DAO 与会计准则解耦。
 */
@Repository
public class FinancialStatementDao {

    /**
     * 科目借贷累计净额原料行：截至/区间内该科目已过账凭证行的借/贷合计。
     * net（借方向）= totalDebit − totalCredit；service 侧据科目类别/余额方向归集报表行。
     */
    public record AccountNetRow(String accountCode, BigDecimal totalDebit, BigDecimal totalCredit) {
    }

    private static final RowMapper<AccountNetRow> NET_MAPPER = (rs, n) -> new AccountNetRow(
            rs.getString("account_code"),
            nz(rs.getBigDecimal("total_debit")),
            nz(rs.getBigDecimal("total_credit")));

    private final JdbcTemplate jdbc;

    public FinancialStatementDao(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    /**
     * 资产负债表派生（设计真源 §1.1）：科目累计净额，截至账期 P 末（时点，含 P）。
     *
     * <p>取<b>全部</b>已过账凭证（status='APPROVED'），<b>含</b>结转损益凭证——4103 本年利润因此
     * 承接累计净利润；损益类经结转后累计净额=0，正确不进资产负债表。按 account_code 分组聚合。
     *
     * @param period 账期键 yyyyMM（6 位）
     * @return 截至 P 末有过账记录的各科目借贷累计净额行
     */
    @Transactional(readOnly = true)
    public List<AccountNetRow> cumulativeBalances(String period) {
        Objects.requireNonNull(period, "period 不能为空");
        String sql = "SELECT vl.account_code AS account_code,"
                + " SUM(vl.debit) AS total_debit,"
                + " SUM(vl.credit) AS total_credit"
                + " FROM voucher_line vl"
                + " JOIN voucher v ON vl.voucher_id = v.id"
                + " WHERE v.tenant_id = 0 AND v.status = 'APPROVED' AND v.period <= ?"
                + " GROUP BY vl.account_code";
        return jdbc.query(sql, NET_MAPPER, period);
    }

    /**
     * 利润表派生（设计真源 §1.2）：损益/成本类发生额，账期区间内（本期 [P,P]；本年累计 [yyyy01,P]）。
     *
     * <p><b>排除结转损益凭证</b>（source_doc_type &lt;&gt; 'PERIOD_CLOSING'）：利润表反映经营活动的
     * 收入/费用，结转凭证是内部期末结转（把损益冲入 4103），若计入则损益类发生额自相抵消归零、
     * 利润表全为 0。排除后损益类发生额=经营真实发生额，且净利润（利润表）==结转净利润==4103 本期变动。
     *
     * @param fromPeriod 区间起始账期键 yyyyMM（含）
     * @param toPeriod   区间结束账期键 yyyyMM（含）
     * @return 区间内有发生额的各科目借贷合计行（service 侧取损益/成本类做利润表）
     */
    @Transactional(readOnly = true)
    public List<AccountNetRow> profitLossMovements(String fromPeriod, String toPeriod) {
        Objects.requireNonNull(fromPeriod, "fromPeriod 不能为空");
        Objects.requireNonNull(toPeriod, "toPeriod 不能为空");
        String sql = "SELECT vl.account_code AS account_code,"
                + " SUM(vl.debit) AS total_debit,"
                + " SUM(vl.credit) AS total_credit"
                + " FROM voucher_line vl"
                + " JOIN voucher v ON vl.voucher_id = v.id"
                + " WHERE v.tenant_id = 0 AND v.status = 'APPROVED'"
                + " AND v.period BETWEEN ? AND ?"
                + " AND (v.source_doc_type IS NULL OR v.source_doc_type <> 'PERIOD_CLOSING')"
                + " GROUP BY vl.account_code";
        return jdbc.query(sql, NET_MAPPER, fromPeriod, toPeriod);
    }

    /** SUM/聚合列可能为 NULL（空集），统一收敛为 0。 */
    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
