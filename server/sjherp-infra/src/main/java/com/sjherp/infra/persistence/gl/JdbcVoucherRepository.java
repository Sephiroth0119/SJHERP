package com.sjherp.infra.persistence.gl;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.gl.AccountBalance;
import com.sjherp.domain.gl.Voucher;
import com.sjherp.domain.gl.VoucherLine;
import com.sjherp.domain.gl.VoucherQuery;
import com.sjherp.domain.gl.VoucherRepository;

/**
 * 凭证仓储的 MySQL 实现（M4-T01；代码风格照 {@code JdbcPurchaseInvoiceRepository}）。
 *
 * <p>聚合落库：新建时插头（{@link GeneratedKeyHolder} 回填头 id）+ 批量插行（回填各行 id）；
 * 已存在时只更新头状态/冲销关联/审计字段（行集合建单后不增删、内容不变，更新时不触行表）。
 * 读取按单据号装配整聚合（行按 line_no 有序）。tenant_id v1.0 恒 0（ADR-002）；
 * 时间列 DATETIME(6) 按 UTC 读写。
 *
 * <p>{@link #aggregateBalances} 派生科目余额：仅统计 status='APPROVED' 的凭证行
 * （命中 idx_voucher_line_account），供试算平衡/科目余额用——T01 不维护期末余额表（拆解 §8 决策 2）。
 */
@Transactional
public class JdbcVoucherRepository implements VoucherRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, period, voucher_date, word, total_amount, summary, source_doc_no, "
                    + "source_doc_type, status, created_by FROM voucher ";

    private final JdbcTemplate jdbc;

    public JdbcVoucherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Voucher voucher) {
        Long headId = findHeadId(voucher.getDocNo());
        if (headId == null) {
            insert(voucher);
        } else {
            update(headId, voucher);
        }
    }

    private void insert(Voucher voucher) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO voucher (doc_no, period, voucher_date, word, total_amount, summary, "
                            + "source_doc_no, source_doc_type, status, reversal_of_id, reversed_by_id, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, voucher.getDocNo());
            ps.setString(2, voucher.getPeriod());
            ps.setObject(3, voucher.getVoucherDate());
            ps.setString(4, voucher.getWord());
            ps.setBigDecimal(5, voucher.getTotalAmount());
            setNullableString(ps, 6, voucher.getSummary());
            setNullableString(ps, 7, voucher.getSourceDocNo());
            setNullableString(ps, 8, voucher.getSourceDocType());
            ps.setString(9, voucher.getStatus().name());
            setNullableString(ps, 10, voucher.getReversalOfId());
            setNullableString(ps, 11, voucher.getReversedById());
            ps.setString(12, voucher.getCreatedBy());
            ps.setObject(13, toDb(voucher.getCreatedAt()));
            ps.setString(14, voucher.getUpdatedBy());
            ps.setObject(15, toDb(voucher.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得凭证头自增主键").longValue();
        voucher.assignId(headId);

        for (VoucherLine line : voucher.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, VoucherLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO voucher_line (voucher_id, line_no, account_code, debit, credit, summary) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setString(3, line.getAccountCode());
            ps.setBigDecimal(4, line.getDebit());
            ps.setBigDecimal(5, line.getCredit());
            setNullableString(ps, 6, line.getSummary());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得凭证行自增主键").longValue());
    }

    private void update(long headId, Voucher voucher) {
        // 行集合与凭证业务内容建单后不变，更新只改头状态/冲销关联/审计字段（不触行表）
        jdbc.update("UPDATE voucher SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                voucher.getStatus().name(), voucher.getReversalOfId(), voucher.getReversedById(),
                voucher.getUpdatedBy(), toDb(voucher.getUpdatedAt()), headId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Voucher> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Voucher> search(VoucherQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.period() != null && !query.period().isBlank()) {
            where.append("AND period = ? ");
            args.add(query.period().strip());
        }
        if (query.status() != null && !query.status().isBlank()) {
            where.append("AND status = ? ");
            args.add(query.status().strip());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM voucher " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                HEAD_ROW_MAPPER, pageArgs.toArray());

        List<Voucher> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Voucher> findBySourceDocNo(String sourceDocNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND source_doc_no = ? "
                + "ORDER BY id", HEAD_ROW_MAPPER, sourceDocNo);
        List<Voucher> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return documents;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountBalance> aggregateBalances(String period) {
        // 统计已过账（APPROVED）+ 已冲销（REVERSED）凭证行（M4-T07a 红字法关键）：红字法下原凭证被冲销后
        // 转 REVERSED，其经济发生额已真实入账、由其借贷对调的红字凭证（APPROVED）抵消，二者必须都计入科目
        // 发生额才能净额归零——否则原凭证被整笔剔除、仅红字计入，科目余额被写成真实业务的反方向（错账）。
        // 草稿（DRAFT）/作废（CANCELLED）未过账，不计入。命中 idx_voucher_line_account。
        return jdbc.query(
                "SELECT vl.account_code AS account_code, "
                        + "COALESCE(SUM(vl.debit), 0) AS total_debit, "
                        + "COALESCE(SUM(vl.credit), 0) AS total_credit "
                        + "FROM voucher_line vl JOIN voucher v ON vl.voucher_id = v.id "
                        + "WHERE v.tenant_id = 0 AND v.period = ? AND v.status IN ('APPROVED', 'REVERSED') "
                        + "GROUP BY vl.account_code ORDER BY vl.account_code",
                (rs, rowNum) -> new AccountBalance(
                        rs.getString("account_code"),
                        rs.getBigDecimal("total_debit"),
                        rs.getBigDecimal("total_credit")),
                period);
    }

    private Voucher toDocument(HeadRow head) {
        List<VoucherLine> lines = loadLines(head.id());
        return Voucher.restore(head.docNo(), head.period(), head.voucherDate(), head.word(),
                head.totalAmount(), head.summary(), head.sourceDocType(), head.sourceDocNo(),
                head.status(), lines, head.createdBy());
    }

    private List<VoucherLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, account_code, debit, credit, summary "
                        + "FROM voucher_line WHERE voucher_id = ? ORDER BY line_no",
                (rs, rowNum) -> VoucherLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getString("account_code"),
                        rs.getBigDecimal("debit"),
                        rs.getBigDecimal("credit"),
                        rs.getString("summary")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM voucher WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, String period, LocalDate voucherDate, String word,
                           BigDecimal totalAmount, String summary, String sourceDocNo,
                           String sourceDocType, DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getString("period"),
            rs.getObject("voucher_date", LocalDate.class),
            rs.getString("word"),
            rs.getBigDecimal("total_amount"),
            rs.getString("summary"),
            rs.getString("source_doc_no"),
            rs.getString("source_doc_type"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
