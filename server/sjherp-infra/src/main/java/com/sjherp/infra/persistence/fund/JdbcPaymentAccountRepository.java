package com.sjherp.infra.persistence.fund;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
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

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.fund.PaymentAccount;
import com.sjherp.domain.fund.PaymentAccountQuery;
import com.sjherp.domain.fund.PaymentAccountRepository;
import com.sjherp.domain.fund.PaymentAccountType;

/**
 * 资金账户仓储的 MySQL 实现（M4-T04a，模式样板：{@code JdbcWarehouseRepository}）。
 *
 * <p>时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcWarehouseRepository）。
 */
@Transactional
public class JdbcPaymentAccountRepository implements PaymentAccountRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, code, name, account_type, gl_account_code, bank_name, account_no, status, "
                    + "created_by, created_at, updated_by, updated_at FROM payment_account ";

    private static final RowMapper<PaymentAccount> ROW_MAPPER = (rs, rowNum) -> PaymentAccount.restore(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            PaymentAccountType.valueOf(rs.getString("account_type")),
            rs.getString("gl_account_code"),
            rs.getString("bank_name"),
            rs.getString("account_no"),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcPaymentAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PaymentAccount account) {
        if (account.getId() == null) {
            insert(account);
        } else {
            update(account);
        }
    }

    private void insert(PaymentAccount account) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO payment_account (code, name, account_type, gl_account_code, bank_name, "
                            + "account_no, status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, account.getCode());
            ps.setString(2, account.getName());
            ps.setString(3, account.getAccountType().name());
            ps.setString(4, account.getGlAccountCode());
            ps.setString(5, account.getBankName());
            ps.setString(6, account.getAccountNo());
            ps.setString(7, account.getStatus().name());
            ps.setString(8, account.getCreatedBy());
            ps.setObject(9, toDb(account.getCreatedAt()));
            ps.setString(10, account.getUpdatedBy());
            ps.setObject(11, toDb(account.getUpdatedAt()));
            return ps;
        }, keyHolder);
        account.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    private void update(PaymentAccount account) {
        // 创建审计字段（created_by/created_at）落库后不可变，更新不触碰
        jdbc.update("UPDATE payment_account SET code = ?, name = ?, account_type = ?, gl_account_code = ?, "
                        + "bank_name = ?, account_no = ?, status = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                account.getCode(), account.getName(), account.getAccountType().name(),
                account.getGlAccountCode(), account.getBankName(), account.getAccountNo(),
                account.getStatus().name(), account.getUpdatedBy(), toDb(account.getUpdatedAt()),
                account.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentAccount> findById(long id) {
        List<PaymentAccount> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentAccount> findByCode(String code) {
        List<PaymentAccount> rows = jdbc.query(SELECT_COLUMNS + "WHERE code = ?", ROW_MAPPER, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_account WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<PaymentAccount> search(PaymentAccountQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.keyword() != null) {
            // 关键字模糊匹配编码/名称/开户行（中缀 LIKE，小企业数据量可接受）
            String like = "%" + escapeLike(query.keyword()) + "%";
            where.append("AND (code LIKE ? OR name LIKE ? OR bank_name LIKE ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM payment_account " + where, Long.class,
                args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<PaymentAccount> rows = jdbc.query(SELECT_COLUMNS + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(rows, totalCount, query.page(), query.size());
    }

    /** LIKE 通配符转义（% _ \），避免关键字里的通配符放大匹配范围 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
