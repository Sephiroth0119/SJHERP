package com.sjherp.infra.persistence.production;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.DocumentStatus;
import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MaterialIssue;
import com.sjherp.domain.production.MaterialIssueLine;
import com.sjherp.domain.production.MaterialIssueQuery;
import com.sjherp.domain.production.MaterialIssueRepository;

/**
 * 领料单 MySQL 仓储实现（M5-T04）。
 *
 * <p>照 {@link JdbcWorkOrderRepository} 范式：头行分开保存；行先删后插（简单全量替换）；
 * 分页查询用 COUNT+LIMIT/OFFSET；BigDecimal 精度来自 DB DECIMAL 定义，不额外转换。
 * 时间列全程 UTC DATETIME(6)，{@code LocalDateTime} ↔ {@code Instant} 经 {@code ZoneOffset.UTC} 转换。
 */
@Transactional
public class JdbcMaterialIssueRepository implements MaterialIssueRepository {

    private static final long TENANT_ID = 0L;

    /** 领料单头 SELECT 公共前缀 */
    private static final String SELECT_HEAD =
            "SELECT id, doc_no, work_order_doc_no, warehouse_id, remark, status, "
                    + "reversal_of_id, reversed_by_id, created_by "
                    + "FROM material_issue ";

    private final JdbcTemplate jdbc;

    public JdbcMaterialIssueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- save

    @Override
    public void save(MaterialIssue mi) {
        if (mi.getId() == null) {
            insert(mi);
        } else {
            update(mi);
        }
        saveLines(mi);
    }

    private void insert(MaterialIssue mi) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = toDb(Instant.now());
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO material_issue "
                            + "(tenant_id, doc_no, work_order_doc_no, warehouse_id, remark, status, "
                            + "reversal_of_id, reversed_by_id, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, TENANT_ID);
            ps.setString(2, mi.getDocNo());
            ps.setString(3, mi.getWorkOrderDocNo());
            ps.setLong(4, mi.getWarehouseId());
            ps.setString(5, mi.getRemark());
            ps.setString(6, mi.getStatus().name());
            ps.setString(7, mi.getReversalOfId());
            ps.setString(8, mi.getReversedById());
            ps.setString(9, mi.getCreatedBy());
            ps.setObject(10, now);
            ps.setString(11, mi.getUpdatedBy());
            ps.setObject(12, now);
            return ps;
        }, keyHolder);
        mi.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得领料单自增主键").longValue());
    }

    private void update(MaterialIssue mi) {
        LocalDateTime now = toDb(Instant.now());
        // 持久化冲销链路标记，与全部兄弟单据范式一致
        jdbc.update(
                "UPDATE material_issue SET status=?, remark=?, "
                        + "reversal_of_id=?, reversed_by_id=?, "
                        + "updated_by=?, updated_at=? WHERE tenant_id=? AND id=?",
                mi.getStatus().name(),
                mi.getRemark(),
                mi.getReversalOfId(),
                mi.getReversedById(),
                mi.getUpdatedBy(),
                now,
                TENANT_ID,
                mi.getId());
    }

    /**
     * 行先删后插——全量替换，简化乐观锁复杂度。
     * 过账后 issued_cost 已回填到聚合根行，重新插入即持久化。
     */
    private void saveLines(MaterialIssue mi) {
        long headId = mi.getId();

        // 删除旧行
        jdbc.update(
                "DELETE FROM material_issue_line WHERE tenant_id=? AND material_issue_id=?",
                TENANT_ID, headId);

        // 批量插入新行
        for (MaterialIssueLine line : mi.getLines()) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO material_issue_line "
                                + "(tenant_id, material_issue_id, line_no, product_id, "
                                + "required_qty, quantity, unit_id, issued_cost) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, TENANT_ID);
                ps.setLong(2, headId);
                ps.setInt(3, line.getLineNo());
                ps.setLong(4, line.getProductId());
                ps.setBigDecimal(5, line.getRequiredQty());
                ps.setBigDecimal(6, line.getQuantity());
                ps.setLong(7, line.getUnitId());
                // issued_cost 过账前为 null，过账后回填
                if (line.getIssuedCost() != null) {
                    ps.setBigDecimal(8, line.getIssuedCost());
                } else {
                    ps.setNull(8, java.sql.Types.DECIMAL);
                }
                return ps;
            }, keyHolder);
            if (line.getId() == null) {
                line.assignId(Objects.requireNonNull(keyHolder.getKey(),
                        "未取得领料单行自增主键").longValue());
            }
        }
    }

    // ---------------------------------------------------------------- findByDocNo

    @Override
    @Transactional(readOnly = true)
    public Optional<MaterialIssue> findByDocNo(String docNo) {
        List<MaterialIssue> rows = jdbc.query(
                SELECT_HEAD + "WHERE tenant_id=? AND doc_no=?",
                (rs, rn) -> mapHead(rs), TENANT_ID, docNo);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        MaterialIssue head = rows.get(0);
        List<MaterialIssueLine> lines = loadLines(head.getId());
        // 重建携带行的完整聚合根
        return Optional.of(MaterialIssue.restore(
                head.getId(),
                head.getDocNo(),
                head.getWorkOrderDocNo(),
                head.getWarehouseId(),
                head.getRemark(),
                head.getStatus(),
                head.getReversalOfId(),
                head.getReversedById(),
                lines,
                head.getCreatedBy(),
                head.getUpdatedBy()));
    }

    // ---------------------------------------------------------------- search

    @Override
    @Transactional(readOnly = true)
    public PageResult<MaterialIssue> search(MaterialIssueQuery query) {
        StringBuilder where = new StringBuilder("WHERE tenant_id=? ");
        List<Object> params = new ArrayList<>();
        params.add(TENANT_ID);

        if (query.workOrderDocNo() != null) {
            where.append("AND work_order_doc_no=? ");
            params.add(query.workOrderDocNo());
        }
        if (query.status() != null) {
            where.append("AND status=? ");
            params.add(query.status().name());
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM material_issue " + where,
                Long.class, params.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, query.page(), query.size());
        }

        params.add(query.size());
        params.add((long) (query.page() - 1) * query.size());
        List<MaterialIssue> heads = jdbc.query(
                SELECT_HEAD + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rn) -> mapHead(rs), params.toArray());

        // 批量加载行（小企业页面量小，N+1 可接受）
        List<MaterialIssue> fullItems = new ArrayList<>(heads.size());
        for (MaterialIssue head : heads) {
            List<MaterialIssueLine> lines = loadLines(head.getId());
            fullItems.add(MaterialIssue.restore(
                    head.getId(),
                    head.getDocNo(),
                    head.getWorkOrderDocNo(),
                    head.getWarehouseId(),
                    head.getRemark(),
                    head.getStatus(),
                    head.getReversalOfId(),
                    head.getReversedById(),
                    lines,
                    head.getCreatedBy(),
                    head.getUpdatedBy()));
        }
        return new PageResult<>(fullItems, totalCount, query.page(), query.size());
    }

    // ---------------------------------------------------------------- 私有辅助

    /**
     * 加载指定头 id 下的全部行，按行号升序。
     * 金额字段 issued_cost 可为 null（草稿/审核阶段尚未回填）。
     */
    private List<MaterialIssueLine> loadLines(long headId) {
        return jdbc.query(
                "SELECT id, line_no, product_id, required_qty, quantity, unit_id, issued_cost "
                        + "FROM material_issue_line "
                        + "WHERE tenant_id=? AND material_issue_id=? ORDER BY line_no",
                (rs, rn) -> MaterialIssueLine.restore(
                        rs.getLong("id"),
                        rs.getInt("line_no"),
                        rs.getLong("product_id"),
                        rs.getBigDecimal("required_qty"),
                        rs.getBigDecimal("quantity"),
                        rs.getLong("unit_id"),
                        rs.getBigDecimal("issued_cost")  // getBigDecimal 在列为 NULL 时返回 null
                ),
                TENANT_ID, headId);
    }

    /**
     * 从 ResultSet 映射领料单头（不含行）。
     * 由调用方在 loadLines 后重建完整聚合根。
     */
    private MaterialIssue mapHead(java.sql.ResultSet rs) throws java.sql.SQLException {
        // 临时持有 id 以便后续 loadLines；restore 完整签名含 id
        return MaterialIssue.restore(
                rs.getLong("id"),
                rs.getString("doc_no"),
                rs.getString("work_order_doc_no"),
                rs.getLong("warehouse_id"),
                rs.getString("remark"),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getString("reversal_of_id"),
                rs.getString("reversed_by_id"),
                List.of(),   // 行在 loadLines 步骤加载
                rs.getString("created_by"),
                rs.getString("created_by")); // updated_by 占位（查询时不从 SELECT 列返回）
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
