package com.sjherp.infra.persistence.stocktake;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
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

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.stocktake.StockCountDocument;
import com.sjherp.domain.stocktake.StockCountLine;
import com.sjherp.domain.stocktake.StockCountQuery;
import com.sjherp.domain.stocktake.StockCountRepository;

/**
 * 盘点单仓储的 MySQL 实现（M3-T03，拆解 §1.7；代码风格照 {@code JdbcCustomerRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/审计字段，
 * 并按 (stock_count_id, line_no) 更新各行实盘数量（行集合建单后不增删，只录实盘）。
 *
 * <p>tenant_id v1.0 恒 0（ADR-002），由本层落列，领域层不出现。
 * 时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcStockCountRepository implements StockCountRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, warehouse_id, remark, status, created_by FROM stock_count ";

    private final JdbcTemplate jdbc;

    public JdbcStockCountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(StockCountDocument document) {
        Long headId = findHeadId(document.getDocNo());
        if (headId == null) {
            insert(document);
        } else {
            update(headId, document);
        }
    }

    private void insert(StockCountDocument document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO stock_count (doc_no, warehouse_id, remark, status, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, document.getDocNo());
            ps.setLong(2, document.getWarehouseId());
            ps.setString(3, document.getRemark());
            ps.setString(4, document.getStatus().name());
            ps.setString(5, document.getCreatedBy());
            ps.setObject(6, toDb(document.getCreatedAt()));
            ps.setString(7, document.getUpdatedBy());
            ps.setObject(8, toDb(document.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得盘点单头自增主键").longValue();

        for (StockCountLine line : document.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, StockCountLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO stock_count_line (stock_count_id, line_no, product_id, "
                            + "snapshot_qty, counted_qty, entered_unit_cost) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setLong(3, line.getProductId());
            ps.setBigDecimal(4, line.getSnapshotQty());
            if (line.getCountedQty() == null) {
                ps.setNull(5, Types.DECIMAL);
            } else {
                ps.setBigDecimal(5, line.getCountedQty());
            }
            if (line.getEnteredUnitCost() == null) {
                ps.setNull(6, Types.DECIMAL);
            } else {
                ps.setBigDecimal(6, line.getEnteredUnitCost());
            }
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得盘点行自增主键").longValue());
    }

    private void update(long headId, StockCountDocument document) {
        // 创建审计字段落库后不可变；更新只触碰状态、冲销关联与最后操作审计字段
        jdbc.update("UPDATE stock_count SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                document.getStatus().name(), document.getReversalOfId(), document.getReversedById(),
                document.getUpdatedBy(), toDb(document.getUpdatedAt()), headId);
        // 行集合建单后不增删，只更新实盘数量（按 line_no 定位）
        for (StockCountLine line : document.getLines()) {
            if (line.getCountedQty() == null) {
                jdbc.update("UPDATE stock_count_line SET counted_qty = NULL "
                                + "WHERE stock_count_id = ? AND line_no = ?",
                        headId, line.getLineNo());
            } else {
                jdbc.update("UPDATE stock_count_line SET counted_qty = ? "
                                + "WHERE stock_count_id = ? AND line_no = ?",
                        line.getCountedQty(), headId, line.getLineNo());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockCountDocument> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        HeadRow head = heads.get(0);
        List<StockCountLine> lines = loadLines(head.id());
        return Optional.of(StockCountDocument.restore(head.docNo(), head.warehouseId(),
                head.remark(), head.status(), lines, head.createdBy()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StockCountDocument> search(StockCountQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.warehouseId() != null) {
            where.append("AND warehouse_id = ? ");
            args.add(query.warehouseId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM stock_count " + where,
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

        List<StockCountDocument> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            List<StockCountLine> lines = loadLines(head.id());
            documents.add(StockCountDocument.restore(head.docNo(), head.warehouseId(),
                    head.remark(), head.status(), lines, head.createdBy()));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    private List<StockCountLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, product_id, snapshot_qty, counted_qty, entered_unit_cost "
                        + "FROM stock_count_line WHERE stock_count_id = ? ORDER BY line_no",
                (rs, rowNum) -> StockCountLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("snapshot_qty"),
                        rs.getBigDecimal("counted_qty"),
                        rs.getBigDecimal("entered_unit_cost")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM stock_count WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, long warehouseId, String remark,
                           DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getLong("warehouse_id"),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
