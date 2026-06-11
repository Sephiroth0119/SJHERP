package com.sjherp.infra.persistence.partner;

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
import com.sjherp.domain.partner.SettlementMethod;
import com.sjherp.domain.partner.Supplier;
import com.sjherp.domain.partner.SupplierQuery;
import com.sjherp.domain.partner.SupplierRepository;

/**
 * 供应商仓储的 MySQL 实现（模式样板：{@code JdbcProductRepository}）。
 *
 * <p>时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcSupplierRepository implements SupplierRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, code, name, contact_person, contact_phone, address, tax_no, "
                    + "settlement_method, status, created_by, created_at, updated_by, updated_at FROM supplier ";

    private static final RowMapper<Supplier> ROW_MAPPER = (rs, rowNum) -> Supplier.restore(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("contact_person"),
            rs.getString("contact_phone"),
            rs.getString("address"),
            rs.getString("tax_no"),
            SettlementMethod.valueOf(rs.getString("settlement_method")),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcSupplierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Supplier supplier) {
        if (supplier.getId() == null) {
            insert(supplier);
        } else {
            update(supplier);
        }
    }

    private void insert(Supplier supplier) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO supplier (code, name, contact_person, contact_phone, address, tax_no, "
                            + "settlement_method, status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, supplier.getCode());
            ps.setString(2, supplier.getName());
            ps.setString(3, supplier.getContactPerson());
            ps.setString(4, supplier.getContactPhone());
            ps.setString(5, supplier.getAddress());
            ps.setString(6, supplier.getTaxNo());
            ps.setString(7, supplier.getSettlementMethod().name());
            ps.setString(8, supplier.getStatus().name());
            ps.setString(9, supplier.getCreatedBy());
            ps.setObject(10, toDb(supplier.getCreatedAt()));
            ps.setString(11, supplier.getUpdatedBy());
            ps.setObject(12, toDb(supplier.getUpdatedAt()));
            return ps;
        }, keyHolder);
        supplier.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    private void update(Supplier supplier) {
        // 创建审计字段（created_by/created_at）落库后不可变，更新不触碰
        jdbc.update("UPDATE supplier SET code = ?, name = ?, contact_person = ?, contact_phone = ?, "
                        + "address = ?, tax_no = ?, settlement_method = ?, status = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                supplier.getCode(), supplier.getName(), supplier.getContactPerson(),
                supplier.getContactPhone(), supplier.getAddress(), supplier.getTaxNo(),
                supplier.getSettlementMethod().name(), supplier.getStatus().name(),
                supplier.getUpdatedBy(), toDb(supplier.getUpdatedAt()), supplier.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Supplier> findById(long id) {
        List<Supplier> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Supplier> search(SupplierQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.keyword() != null) {
            // 关键字模糊匹配编码/名称/联系人/电话（中缀 LIKE，小企业数据量可接受）
            String like = "%" + escapeLike(query.keyword()) + "%";
            where.append("AND (code LIKE ? OR name LIKE ? OR contact_person LIKE ? OR contact_phone LIKE ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM supplier " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<Supplier> rows = jdbc.query(SELECT_COLUMNS + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
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
