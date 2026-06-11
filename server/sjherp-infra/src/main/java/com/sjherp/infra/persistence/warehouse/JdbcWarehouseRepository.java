package com.sjherp.infra.persistence.warehouse;

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
import com.sjherp.domain.warehouse.Warehouse;
import com.sjherp.domain.warehouse.WarehouseQuery;
import com.sjherp.domain.warehouse.WarehouseRepository;

/**
 * 仓库仓储的 MySQL 实现（模式样板：{@code JdbcProductRepository}）。
 *
 * <p>时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcWarehouseRepository implements WarehouseRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, code, name, address, manager, location_enabled, status, "
                    + "created_by, created_at, updated_by, updated_at FROM warehouse ";

    private static final RowMapper<Warehouse> ROW_MAPPER = (rs, rowNum) -> Warehouse.restore(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("address"),
            rs.getString("manager"),
            rs.getBoolean("location_enabled"),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcWarehouseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Warehouse warehouse) {
        if (warehouse.getId() == null) {
            insert(warehouse);
        } else {
            update(warehouse);
        }
    }

    private void insert(Warehouse warehouse) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO warehouse (code, name, address, manager, location_enabled, status, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, warehouse.getCode());
            ps.setString(2, warehouse.getName());
            ps.setString(3, warehouse.getAddress());
            ps.setString(4, warehouse.getManager());
            ps.setBoolean(5, warehouse.isLocationEnabled());
            ps.setString(6, warehouse.getStatus().name());
            ps.setString(7, warehouse.getCreatedBy());
            ps.setObject(8, toDb(warehouse.getCreatedAt()));
            ps.setString(9, warehouse.getUpdatedBy());
            ps.setObject(10, toDb(warehouse.getUpdatedAt()));
            return ps;
        }, keyHolder);
        warehouse.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    private void update(Warehouse warehouse) {
        // 创建审计字段（created_by/created_at）落库后不可变，更新不触碰
        jdbc.update("UPDATE warehouse SET code = ?, name = ?, address = ?, manager = ?, "
                        + "location_enabled = ?, status = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                warehouse.getCode(), warehouse.getName(), warehouse.getAddress(),
                warehouse.getManager(), warehouse.isLocationEnabled(), warehouse.getStatus().name(),
                warehouse.getUpdatedBy(), toDb(warehouse.getUpdatedAt()), warehouse.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Warehouse> findById(long id) {
        List<Warehouse> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warehouse WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Warehouse> search(WarehouseQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.keyword() != null) {
            // 关键字模糊匹配编码/名称/负责人（中缀 LIKE，小企业数据量可接受）
            String like = "%" + escapeLike(query.keyword()) + "%";
            where.append("AND (code LIKE ? OR name LIKE ? OR manager LIKE ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM warehouse " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<Warehouse> rows = jdbc.query(SELECT_COLUMNS + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
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
