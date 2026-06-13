package com.sjherp.infra.persistence.transfer;

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

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.transfer.TransferDocument;
import com.sjherp.domain.transfer.TransferLine;
import com.sjherp.domain.transfer.TransferQuery;
import com.sjherp.domain.transfer.TransferRepository;

/**
 * 调拨单仓储的 MySQL 实现（M3-T04，拆解 §1.6.5；代码风格照 {@code JdbcStockCountRepository}）。
 *
 * <p>聚合落库：新建时插头 + 批量插行（回填各自增 id）；已存在时更新头状态/冲销关联/审计字段
 * （行集合建单后不增删、内容不变，更新时不触碰行表）。
 *
 * <p>tenant_id v1.0 恒 0（ADR-002），由本层落列，领域层不出现。
 * 时间列 DATETIME(6) 一律按 UTC 读写（约定同 JdbcAgentSessionRepository）。
 */
@Transactional
public class JdbcTransferRepository implements TransferRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, from_warehouse_id, to_warehouse_id, remark, status, created_by "
                    + "FROM stock_transfer ";

    private final JdbcTemplate jdbc;

    public JdbcTransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(TransferDocument document) {
        Long headId = findHeadId(document.getDocNo());
        if (headId == null) {
            insert(document);
        } else {
            update(headId, document);
        }
    }

    private void insert(TransferDocument document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO stock_transfer (doc_no, from_warehouse_id, to_warehouse_id, remark, "
                            + "status, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, document.getDocNo());
            ps.setLong(2, document.getFromWarehouseId());
            ps.setLong(3, document.getToWarehouseId());
            ps.setString(4, document.getRemark());
            ps.setString(5, document.getStatus().name());
            ps.setString(6, document.getCreatedBy());
            ps.setObject(7, toDb(document.getCreatedAt()));
            ps.setString(8, document.getUpdatedBy());
            ps.setObject(9, toDb(document.getUpdatedAt()));
            return ps;
        }, keyHolder);
        long headId = Objects.requireNonNull(keyHolder.getKey(), "未取得调拨单头自增主键").longValue();

        for (TransferLine line : document.getLines()) {
            insertLine(headId, line);
        }
    }

    private void insertLine(long headId, TransferLine line) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO stock_transfer_line (stock_transfer_id, line_no, product_id, quantity) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, headId);
            ps.setInt(2, line.getLineNo());
            ps.setLong(3, line.getProductId());
            ps.setBigDecimal(4, line.getQuantity());
            return ps;
        }, keyHolder);
        line.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得调拨行自增主键").longValue());
    }

    private void update(long headId, TransferDocument document) {
        // 创建审计字段与行内容落库后不可变；更新只触碰状态、冲销关联与最后操作审计字段
        jdbc.update("UPDATE stock_transfer SET status = ?, reversal_of_id = ?, reversed_by_id = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                document.getStatus().name(), document.getReversalOfId(), document.getReversedById(),
                document.getUpdatedBy(), toDb(document.getUpdatedAt()), headId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TransferDocument> findByDocNo(String docNo) {
        List<HeadRow> heads = jdbc.query(SELECT_HEAD + "WHERE tenant_id = 0 AND doc_no = ?",
                HEAD_ROW_MAPPER, docNo);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toDocument(heads.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TransferDocument> search(TransferQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.warehouseId() != null) {
            // 命中调出仓或调入仓任一即匹配
            where.append("AND (from_warehouse_id = ? OR to_warehouse_id = ?) ");
            args.add(query.warehouseId());
            args.add(query.warehouseId());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfer " + where,
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

        List<TransferDocument> documents = new ArrayList<>(heads.size());
        for (HeadRow head : heads) {
            documents.add(toDocument(head));
        }
        return new PageResult<>(documents, totalCount, query.page(), query.size());
    }

    private TransferDocument toDocument(HeadRow head) {
        List<TransferLine> lines = loadLines(head.id());
        return TransferDocument.restore(head.docNo(), head.fromWarehouseId(), head.toWarehouseId(),
                head.remark(), head.status(), lines, head.createdBy());
    }

    private List<TransferLine> loadLines(long headId) {
        return jdbc.query("SELECT id, line_no, product_id, quantity "
                        + "FROM stock_transfer_line WHERE stock_transfer_id = ? ORDER BY line_no",
                (rs, rowNum) -> TransferLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("quantity")),
                headId);
    }

    private Long findHeadId(String docNo) {
        List<Long> ids = jdbc.query("SELECT id FROM stock_transfer WHERE tenant_id = 0 AND doc_no = ?",
                (rs, rowNum) -> rs.getLong("id"), docNo);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 头行读模型（restore 工厂所需字段；created_at/updated_at 由审计日志承载，不进领域聚合） */
    private record HeadRow(long id, String docNo, long fromWarehouseId, long toWarehouseId,
                           String remark, DocumentStatus status, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_ROW_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            rs.getLong("from_warehouse_id"),
            rs.getLong("to_warehouse_id"),
            rs.getString("remark"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"));

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
