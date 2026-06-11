package com.sjherp.infra.persistence.catalog;

import java.sql.PreparedStatement;
import java.sql.Statement;
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

import com.sjherp.domain.catalog.Unit;
import com.sjherp.domain.catalog.UnitRepository;

/**
 * 计量单位仓储的 MySQL 实现。
 *
 * <p>写入约定与 JdbcAgentSessionRepository 一致：时间列 DATETIME(6)，
 * 读写一律按 UTC LocalDateTime 转换，与连接时区解耦。
 */
@Transactional
public class JdbcUnitRepository implements UnitRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, name, unit_precision, created_by, created_at, updated_by, updated_at FROM unit ";

    private static final RowMapper<Unit> ROW_MAPPER = (rs, rowNum) -> Unit.restore(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getInt("unit_precision"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcUnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Unit unit) {
        if (unit.getId() == null) {
            // 新建：取回自增主键回填聚合
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO unit (name, unit_precision, created_by, created_at, updated_by, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, unit.getName());
                ps.setInt(2, unit.getPrecision());
                ps.setString(3, unit.getCreatedBy());
                ps.setObject(4, toDb(unit.getCreatedAt()));
                ps.setString(5, unit.getUpdatedBy());
                ps.setObject(6, toDb(unit.getUpdatedAt()));
                return ps;
            }, keyHolder);
            unit.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
        } else {
            jdbc.update("UPDATE unit SET name = ?, unit_precision = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                    unit.getName(), unit.getPrecision(), unit.getUpdatedBy(), toDb(unit.getUpdatedAt()),
                    unit.getId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Unit> findById(long id) {
        List<Unit> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Unit> findByName(String name) {
        List<Unit> rows = jdbc.query(SELECT_COLUMNS + "WHERE name = ?", ROW_MAPPER, name);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Unit> findAll() {
        return jdbc.query(SELECT_COLUMNS + "ORDER BY id", ROW_MAPPER);
    }

    @Override
    public void deleteById(long id) {
        jdbc.update("DELETE FROM unit WHERE id = ?", id);
    }

    /** Instant -> UTC LocalDateTime（DATETIME(6) 列无时区，统一按 UTC 存） */
    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** UTC LocalDateTime -> Instant */
    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
