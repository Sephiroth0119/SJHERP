package com.sjherp.infra.persistence.payable;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.payable.AccountsPayable;
import com.sjherp.domain.payable.AccountsPayableQuery;
import com.sjherp.domain.payable.AccountsPayableRepository;
import com.sjherp.domain.payable.PayableStatus;

/**
 * 应付账款仓储的 MySQL 实现（M3-T07；代码风格照 {@code JdbcTransferRepository}）。
 *
 * <p>应付台账记录只追加、不修改（CLAUDE.md 原则 2）：本期只有 insert + 查询，核销更新 M4-T03。
 * tenant_id v1.0 恒 0（ADR-002）；时间列 DATETIME(6) 按 UTC 读写；金额 DECIMAL。
 */
@Transactional
public class JdbcAccountsPayableRepository implements AccountsPayableRepository {

    private static final String SELECT_ALL =
            "SELECT id, supplier_id, amount, source_doc_no, due_date, status, settled_amount, "
                    + "created_by, created_at FROM accounts_payable ";

    private final JdbcTemplate jdbc;

    public JdbcAccountsPayableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AccountsPayable payable) {
        // 应付只追加（已分配 id 的不重复落库——本期不更新；核销 M4-T03）
        if (payable.getId() != null) {
            return;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts_payable (supplier_id, amount, source_doc_no, due_date, "
                            + "status, settled_amount, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, payable.getSupplierId());
            ps.setBigDecimal(2, payable.getAmount());
            ps.setString(3, payable.getSourceDocNo());
            ps.setObject(4, payable.getDueDate());
            ps.setString(5, payable.getStatus().name());
            ps.setBigDecimal(6, payable.getSettledAmount());
            ps.setString(7, payable.getCreatedBy());
            ps.setObject(8, toDb(payable.getCreatedAt()));
            return ps;
        }, keyHolder);
        payable.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得应付账款自增主键").longValue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountsPayable> findBySourceDocNo(String sourceDocNo) {
        return jdbc.query(SELECT_ALL + "WHERE tenant_id = 0 AND source_doc_no = ?",
                ROW_MAPPER, sourceDocNo);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AccountsPayable> search(AccountsPayableQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.supplierId() != null) {
            where.append("AND supplier_id = ? ");
            args.add(query.supplierId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM accounts_payable " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<AccountsPayable> items = jdbc.query(SELECT_ALL + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());
        return new PageResult<>(items, totalCount, query.page(), query.size());
    }

    private static final RowMapper<AccountsPayable> ROW_MAPPER = (rs, rowNum) -> AccountsPayable.restore(
            rs.getLong("id"),
            rs.getLong("supplier_id"),
            rs.getBigDecimal("amount"),
            rs.getString("source_doc_no"),
            rs.getObject("due_date", LocalDate.class),
            PayableStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("settled_amount"),
            rs.getString("created_by"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
