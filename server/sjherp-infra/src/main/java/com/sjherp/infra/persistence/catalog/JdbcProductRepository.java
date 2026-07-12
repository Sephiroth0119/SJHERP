package com.sjherp.infra.persistence.catalog;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.InventoryCategory;
import com.sjherp.domain.catalog.ProductQuery;
import com.sjherp.domain.catalog.ProductRepository;
import com.sjherp.domain.catalog.UnitConversion;
import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;

/**
 * 商品仓储的 MySQL 实现。
 *
 * <p>聚合整体读写：save 在同一事务内持久化商品行与换算表（换算表整体
 * 替换——先删后插，从属值对象无独立生命周期）；findById/search 回带完整
 * 换算表。时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcProductRepository implements ProductRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, code, name, spec, category_id, inventory_category, base_unit_id, barcode, status, remark, "
                    + "created_by, created_at, updated_by, updated_at FROM product ";

    /** 商品行中间载体（换算表单独查询后再 restore 成聚合） */
    private record ProductRow(long id, String code, String name, String spec, Long categoryId,
                              InventoryCategory inventoryCategory,
                              long baseUnitId, String barcode, ArchiveStatus status, String remark,
                              String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    private static final RowMapper<ProductRow> ROW_MAPPER = (rs, rowNum) -> new ProductRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("spec"),
            rs.getObject("category_id", Long.class),
            InventoryCategory.valueOf(rs.getString("inventory_category")),
            rs.getLong("base_unit_id"),
            rs.getString("barcode"),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("remark"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static final RowMapper<UnitConversion> CONVERSION_ROW_MAPPER = (rs, rowNum) ->
            new UnitConversion(rs.getLong("unit_id"), rs.getBigDecimal("rate"));

    private final JdbcTemplate jdbc;

    public JdbcProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Product product) {
        if (product.getId() == null) {
            insertProduct(product);
        } else {
            updateProduct(product);
            // 换算表整体替换：先清空再重插（值对象无独立生命周期，量级很小）
            jdbc.update("DELETE FROM product_unit_conversion WHERE product_id = ?", product.getId());
        }
        insertConversions(product);
    }

    private void insertProduct(Product product) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO product (code, name, spec, category_id, inventory_category, base_unit_id, barcode, status, remark, "
                            + "created_by, created_at, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, product.getCode());
            ps.setString(2, product.getName());
            ps.setString(3, product.getSpec());
            if (product.getCategoryId() == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, product.getCategoryId());
            }
            ps.setString(5, product.getInventoryCategory().name());
            ps.setLong(6, product.getBaseUnitId());
            ps.setString(7, product.getBarcode());
            ps.setString(8, product.getStatus().name());
            ps.setString(9, product.getRemark());
            ps.setString(10, product.getCreatedBy());
            ps.setObject(11, toDb(product.getCreatedAt()));
            ps.setString(12, product.getUpdatedBy());
            ps.setObject(13, toDb(product.getUpdatedAt()));
            return ps;
        }, keyHolder);
        product.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得自增主键").longValue());
    }

    private void updateProduct(Product product) {
        // 创建审计字段（created_by/created_at）落库后不可变，更新不触碰
        jdbc.update("UPDATE product SET code = ?, name = ?, spec = ?, category_id = ?, inventory_category = ?, base_unit_id = ?, "
                        + "barcode = ?, status = ?, remark = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                product.getCode(), product.getName(), product.getSpec(), product.getCategoryId(),
                product.getInventoryCategory().name(), product.getBaseUnitId(), product.getBarcode(), product.getStatus().name(),
                product.getRemark(), product.getUpdatedBy(), toDb(product.getUpdatedAt()),
                product.getId());
    }

    private void insertConversions(Product product) {
        List<UnitConversion> conversions = product.getUnitConversions();
        if (conversions.isEmpty()) {
            return;
        }
        LocalDateTime now = toDb(product.getUpdatedAt());
        jdbc.batchUpdate("INSERT INTO product_unit_conversion (product_id, unit_id, rate, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                conversions, conversions.size(), (ps, conversion) -> {
                    ps.setLong(1, product.getId());
                    ps.setLong(2, conversion.unitId());
                    ps.setBigDecimal(3, conversion.rate());
                    ps.setObject(4, now);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Product> findById(long id) {
        List<ProductRow> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<UnitConversion> conversions = jdbc.query(
                "SELECT unit_id, rate FROM product_unit_conversion WHERE product_id = ? ORDER BY id",
                CONVERSION_ROW_MAPPER, id);
        return Optional.of(restore(rows.get(0), conversions));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCode(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Product> search(ProductQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.keyword() != null) {
            // 关键字模糊匹配编码/名称/条码（中缀 LIKE，小企业数据量可接受）
            String like = "%" + escapeLike(query.keyword()) + "%";
            where.append("AND (code LIKE ? OR name LIKE ? OR barcode LIKE ?) ");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM product " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<ProductRow> rows = jdbc.query(SELECT_COLUMNS + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(attachConversions(rows), totalCount, query.page(), query.size());
    }

    /** 批量回带当页商品的换算表（一次 IN 查询，避免 N+1） */
    private List<Product> attachConversions(List<ProductRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(ProductRow::id).toArray();
        Map<Long, List<UnitConversion>> byProduct = new HashMap<>();
        jdbc.query("SELECT product_id, unit_id, rate FROM product_unit_conversion "
                        + "WHERE product_id IN (" + placeholders + ") ORDER BY id",
                rs -> {
                    byProduct.computeIfAbsent(rs.getLong("product_id"), k -> new ArrayList<>())
                            .add(new UnitConversion(rs.getLong("unit_id"), rs.getBigDecimal("rate")));
                }, ids);
        return rows.stream()
                .map(row -> restore(row, byProduct.getOrDefault(row.id(), List.of())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByCategoryId(long categoryId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product WHERE category_id = ?", Integer.class, categoryId);
        return count != null && count > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByUnitId(long unitId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ("
                        + "SELECT id FROM product WHERE base_unit_id = ? "
                        + "UNION ALL "
                        + "SELECT id FROM product_unit_conversion WHERE unit_id = ?"
                        + ") refs",
                Integer.class, unitId, unitId);
        return count != null && count > 0;
    }

    private static Product restore(ProductRow row, List<UnitConversion> conversions) {
        return Product.restore(row.id(), row.code(), row.name(), row.spec(), row.categoryId(), row.inventoryCategory(),
                row.baseUnitId(), row.barcode(), row.status(), row.remark(), conversions,
                row.createdBy(), row.createdAt(), row.updatedBy(), row.updatedAt());
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
