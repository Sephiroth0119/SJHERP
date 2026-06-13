package com.sjherp.infra.persistence.receivable;

import java.sql.PreparedStatement;
import java.sql.Statement;
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

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.receivable.AccountsReceivable;
import com.sjherp.domain.receivable.ReceivableQuery;
import com.sjherp.domain.receivable.ReceivableRepository;
import com.sjherp.domain.receivable.ReceivableStatus;

/**
 * 应收账款仓储的 MySQL 实现（M3-T10，代码风格照 {@code JdbcSalesInvoiceRepository}）。
 *
 * <p>新建插入 + 回填自增 id；更新（M4-T03 核销）落已核销金额与状态。
 * tenant_id v1.0 恒 0（ADR-002）。时间列 DATETIME(6) 一律按 UTC 读写。
 */
@Transactional
public class JdbcReceivableRepository implements ReceivableRepository {

    private static final String SELECT_ALL =
            "SELECT id, customer_id, amount, settled_amount, source_doc_no, due_date, status, created_by "
                    + "FROM accounts_receivable ";

    private final JdbcTemplate jdbc;

    public JdbcReceivableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AccountsReceivable receivable) {
        if (receivable.getId() == null) {
            insert(receivable);
        } else {
            // M4-T03 核销：落已核销金额与状态
            jdbc.update("UPDATE accounts_receivable SET settled_amount = ?, status = ? WHERE id = ?",
                    receivable.getSettledAmount(), receivable.getStatus().name(), receivable.getId());
        }
    }

    private void insert(AccountsReceivable receivable) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts_receivable (customer_id, amount, settled_amount, "
                            + "source_doc_no, due_date, status, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, receivable.getCustomerId());
            ps.setBigDecimal(2, receivable.getAmount());
            ps.setBigDecimal(3, receivable.getSettledAmount());
            ps.setString(4, receivable.getSourceDocNo());
            ps.setObject(5, receivable.getDueDate());
            ps.setString(6, receivable.getStatus().name());
            ps.setString(7, receivable.getCreatedBy());
            ps.setObject(8, toDb(Instant.now()));
            return ps;
        }, keyHolder);
        receivable.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得应收自增主键").longValue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountsReceivable> findBySourceDocNo(String sourceDocNo) {
        return jdbc.query(SELECT_ALL + "WHERE tenant_id = 0 AND source_doc_no = ? ORDER BY id",
                ROW_MAPPER, sourceDocNo);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountsReceivable> findById(long id) {
        List<AccountsReceivable> rows = jdbc.query(SELECT_ALL + "WHERE tenant_id = 0 AND id = ?",
                ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AccountsReceivable> search(ReceivableQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.customerId() != null) {
            where.append("AND customer_id = ? ");
            args.add(query.customerId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM accounts_receivable " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<AccountsReceivable> items = jdbc.query(
                SELECT_ALL + where + "ORDER BY id DESC LIMIT ? OFFSET ?", ROW_MAPPER, pageArgs.toArray());
        return new PageResult<>(items, totalCount, query.page(), query.size());
    }

    private static final RowMapper<AccountsReceivable> ROW_MAPPER = (rs, rowNum) ->
            AccountsReceivable.restore(
                    rs.getLong("id"),
                    rs.getLong("customer_id"),
                    rs.getBigDecimal("amount"),
                    rs.getBigDecimal("settled_amount"),
                    rs.getString("source_doc_no"),
                    rs.getObject("due_date", LocalDate.class),
                    ReceivableStatus.valueOf(rs.getString("status")),
                    rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
