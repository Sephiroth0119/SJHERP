package com.sjherp.infra.persistence.gl;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.gl.AccountingPeriod;
import com.sjherp.domain.gl.AccountingPeriodRepository;
import com.sjherp.domain.gl.PeriodStatus;

/**
 * 会计期间仓储的 MySQL 实现（M4-T01；代码风格照 {@code JdbcProductRepository}）。
 *
 * <p>单表档案（accounting_period），save 按账期键 upsert：新建插行并回填自增 id；已存在时按 id 更新
 * 状态/关账标记与审计字段（创建审计字段不可变，不触碰）。tenant_id v1.0 恒 0（ADR-002）；
 * 时间列 DATETIME(6) 按 UTC 读写。
 */
@Transactional
public class JdbcAccountingPeriodRepository implements AccountingPeriodRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, period, period_year, period_month, status, closed_by, closed_at, "
                    + "created_by, created_at, updated_by, updated_at FROM accounting_period ";

    private final JdbcTemplate jdbc;

    public JdbcAccountingPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AccountingPeriod period) {
        if (period.getId() == null) {
            insert(period);
        } else {
            update(period);
        }
    }

    private void insert(AccountingPeriod period) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounting_period (period, period_year, period_month, status, "
                            + "closed_by, closed_at, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, period.getPeriod());
            ps.setInt(2, period.getYear());
            ps.setInt(3, period.getMonth());
            ps.setString(4, period.getStatus().name());
            setNullableString(ps, 5, period.getClosedBy());
            setNullableTimestamp(ps, 6, period.getClosedAt());
            ps.setString(7, period.getCreatedBy());
            ps.setObject(8, toDb(period.getCreatedAt()));
            ps.setString(9, period.getUpdatedBy());
            ps.setObject(10, toDb(period.getUpdatedAt()));
            return ps;
        }, keyHolder);
        period.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得账期自增主键").longValue());
    }

    private void update(AccountingPeriod period) {
        // 创建审计字段（created_by/created_at）落库后不可变，更新不触碰；账期键不可改
        jdbc.update("UPDATE accounting_period SET status = ?, closed_by = ?, closed_at = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                period.getStatus().name(), period.getClosedBy(),
                period.getClosedAt() == null ? null : toDb(period.getClosedAt()),
                period.getUpdatedBy(), toDb(period.getUpdatedAt()), period.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountingPeriod> findByPeriod(String period) {
        List<AccountingPeriod> rows = jdbc.query(SELECT_COLUMNS + "WHERE tenant_id = 0 AND period = ?",
                ROW_MAPPER, period);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountingPeriod> findAll() {
        return jdbc.query(SELECT_COLUMNS + "WHERE tenant_id = 0 ORDER BY period", ROW_MAPPER);
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement ps, int index, Instant value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setObject(index, toDb(value));
        }
    }

    private static final RowMapper<AccountingPeriod> ROW_MAPPER = (rs, rowNum) -> AccountingPeriod.restore(
            rs.getLong("id"),
            rs.getString("period"),
            rs.getInt("period_year"),
            rs.getInt("period_month"),
            PeriodStatus.valueOf(rs.getString("status")),
            rs.getString("closed_by"),
            fromDbNullable(rs.getObject("closed_at", LocalDateTime.class)),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }

    private static Instant fromDbNullable(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }
}
