package com.sjherp.infra.persistence.production;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
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

import com.sjherp.domain.common.ArchiveStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.BillOfMaterials;
import com.sjherp.domain.production.BillOfMaterialsQuery;
import com.sjherp.domain.production.BillOfMaterialsRepository;
import com.sjherp.domain.production.BomLine;

/**
 * BOM 物料清单仓储的 MySQL 实现。
 *
 * <p>聚合整体读写：save 在同一事务内持久化头与行（行整体替换——先删后插，
 * 值对象无独立生命周期）；find* 方法批量回带行（一次 IN 查询，避免 N+1）。
 * 时间列 DATETIME(6) 一律按 UTC 读写，与全库约定一致。
 */
@Transactional
public class JdbcBillOfMaterialsRepository implements BillOfMaterialsRepository {

    private static final String SELECT_COLUMNS =
            "SELECT id, product_id, version, status, remark, "
                    + "created_by, created_at, updated_by, updated_at FROM bom ";

    /** BOM 头中间载体（行明细单独查询后再 restore 成聚合） */
    private record BomRow(long id, long productId, int version, ArchiveStatus status, String remark,
                          String createdBy, Instant createdAt, String updatedBy, Instant updatedAt) {
    }

    private static final RowMapper<BomRow> ROW_MAPPER = (rs, rowNum) -> new BomRow(
            rs.getLong("id"),
            rs.getLong("product_id"),
            rs.getInt("version"),
            ArchiveStatus.valueOf(rs.getString("status")),
            rs.getString("remark"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private static final RowMapper<BomLine> LINE_ROW_MAPPER = (rs, rowNum) -> new BomLine(
            rs.getLong("child_product_id"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("scrap_rate"),
            rs.getLong("unit_id"));

    private final JdbcTemplate jdbc;

    public JdbcBillOfMaterialsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(BillOfMaterials bom) {
        if (bom.getId() == null) {
            insertBom(bom);
        } else {
            updateBom(bom);
            // 行整体替换：先清空再重插（值对象无独立生命周期）
            jdbc.update("DELETE FROM bom_line WHERE bom_id = ?", bom.getId());
        }
        insertLines(bom);
    }

    private void insertBom(BillOfMaterials bom) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO bom (product_id, version, status, remark, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, bom.getProductId());
            ps.setInt(2, bom.getVersion());
            ps.setString(3, bom.getStatus().name());
            ps.setString(4, bom.getRemark());
            ps.setString(5, bom.getCreatedBy());
            ps.setObject(6, toDb(bom.getCreatedAt()));
            ps.setString(7, bom.getUpdatedBy());
            ps.setObject(8, toDb(bom.getUpdatedAt()));
            return ps;
        }, keyHolder);
        bom.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得 bom 自增主键").longValue());
    }

    private void updateBom(BillOfMaterials bom) {
        // created_by / created_at 落库后不可变，UPDATE 不触碰
        jdbc.update("UPDATE bom SET product_id = ?, version = ?, status = ?, remark = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                bom.getProductId(), bom.getVersion(), bom.getStatus().name(),
                bom.getRemark(), bom.getUpdatedBy(), toDb(bom.getUpdatedAt()),
                bom.getId());
    }

    private void insertLines(BillOfMaterials bom) {
        List<BomLine> lines = bom.getLines();
        if (lines.isEmpty()) {
            return;
        }
        // line_no 从 1 开始，按列表顺序赋值
        jdbc.batchUpdate(
                "INSERT INTO bom_line (bom_id, line_no, child_product_id, quantity, scrap_rate, unit_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                lines, lines.size(), (ps, line) -> {
                    int lineNo = lines.indexOf(line) + 1;
                    ps.setLong(1, bom.getId());
                    ps.setInt(2, lineNo);
                    ps.setLong(3, line.childProductId());
                    ps.setBigDecimal(4, line.quantity());
                    ps.setBigDecimal(5, line.scrapRate());
                    ps.setLong(6, line.unitId());
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillOfMaterials> findById(long id) {
        List<BomRow> rows = jdbc.query(SELECT_COLUMNS + "WHERE id = ?", ROW_MAPPER, id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<BomLine> lines = jdbc.query(
                "SELECT child_product_id, quantity, scrap_rate, unit_id "
                        + "FROM bom_line WHERE bom_id = ? ORDER BY line_no",
                LINE_ROW_MAPPER, id);
        return Optional.of(restore(rows.get(0), lines));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillOfMaterials> findByProductAndVersion(long productId, int version) {
        List<BomRow> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE product_id = ? AND version = ?",
                ROW_MAPPER, productId, version);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BomRow row = rows.get(0);
        List<BomLine> lines = jdbc.query(
                "SELECT child_product_id, quantity, scrap_rate, unit_id "
                        + "FROM bom_line WHERE bom_id = ? ORDER BY line_no",
                LINE_ROW_MAPPER, row.id());
        return Optional.of(restore(row, lines));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillOfMaterials> findEnabledByProductId(long productId) {
        List<BomRow> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE product_id = ? AND status = 'ENABLED' ORDER BY version",
                ROW_MAPPER, productId);
        return attachLines(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<BillOfMaterials> search(BillOfMaterialsQuery query) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (query.productId() != null) {
            where.append("AND product_id = ? ");
            args.add(query.productId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bom " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((long) (query.page() - 1) * query.size());
        List<BomRow> rows = jdbc.query(
                SELECT_COLUMNS + where + "ORDER BY product_id, version DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());

        return new PageResult<>(attachLines(rows), totalCount, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillOfMaterials> findActiveByProductId(long productId) {
        // active_flag 生成列保证同产品至多一条 ENABLED；直接按 status 查与生成列等价且索引友好
        List<BomRow> rows = jdbc.query(
                SELECT_COLUMNS + "WHERE product_id = ? AND status = 'ENABLED'",
                ROW_MAPPER, productId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BomRow row = rows.get(0);
        List<BomLine> lines = jdbc.query(
                "SELECT child_product_id, quantity, scrap_rate, unit_id "
                        + "FROM bom_line WHERE bom_id = ? ORDER BY line_no",
                LINE_ROW_MAPPER, row.id());
        return Optional.of(restore(row, lines));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findChildProductIds(long parentProductId) {
        // 仅查 ENABLED BOM 下的子件，用于循环引用检测
        return jdbc.queryForList(
                "SELECT DISTINCT bl.child_product_id FROM bom_line bl "
                        + "JOIN bom b ON b.id = bl.bom_id "
                        + "WHERE b.product_id = ? AND b.status = 'ENABLED'",
                Long.class, parentProductId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByProductAndVersion(long productId, int version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bom WHERE product_id = ? AND version = ?",
                Integer.class, productId, version);
        return count != null && count > 0;
    }

    /**
     * 批量回带 BOM 行（一次 IN 查询，避免 N+1）。
     * rows 为空时直接返回空列表。
     */
    private List<BillOfMaterials> attachLines(List<BomRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(BomRow::id).toArray();
        Map<Long, List<BomLine>> byBom = new HashMap<>();
        jdbc.query(
                "SELECT bom_id, child_product_id, quantity, scrap_rate, unit_id "
                        + "FROM bom_line WHERE bom_id IN (" + placeholders + ") ORDER BY bom_id, line_no",
                rs -> {
                    byBom.computeIfAbsent(rs.getLong("bom_id"), k -> new ArrayList<>())
                            .add(new BomLine(
                                    rs.getLong("child_product_id"),
                                    rs.getBigDecimal("quantity"),
                                    rs.getBigDecimal("scrap_rate"),
                                    rs.getLong("unit_id")));
                }, ids);
        return rows.stream()
                .map(row -> restore(row, byBom.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private static BillOfMaterials restore(BomRow row, List<BomLine> lines) {
        return BillOfMaterials.restore(
                row.id(), row.productId(), row.version(), row.status(), row.remark(),
                lines, row.createdBy(), row.createdAt(), row.updatedBy(), row.updatedAt());
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
