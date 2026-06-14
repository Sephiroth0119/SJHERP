package com.sjherp.infra.persistence.production;

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
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.production.MrpRun;
import com.sjherp.domain.production.MrpRunRepository;
import com.sjherp.domain.production.MrpSuggestion;
import com.sjherp.domain.production.SuggestionType;

/**
 * MRP 运行结果 MySQL 持久化（M5-T02）。
 *
 * <p>MRP 运行只写（新建）不更新——regenerative 重跑产生新记录，旧记录只读不改。
 * 头行分离：先插头取自增 id，再批量插 mrp_suggestion 建议行。
 * 历史列表查询（searchHistory）不回带建议行，仅返回头信息（按需加载，避免大查询）。
 * 时间列全程 UTC DATETIME(6)，{@code LocalDateTime} ↔ {@code Instant} 经 {@code ZoneOffset.UTC} 转换。
 */
@Transactional
public class JdbcMrpRunRepository implements MrpRunRepository {

    private static final String SELECT_HEAD =
            "SELECT id, doc_no, run_at, warehouse_id, include_forecast, include_sales_order, "
                    + "remark, created_by, created_at, updated_by, updated_at FROM mrp_run ";

    /** MRP 运行头中间载体 */
    private record HeadRow(long id, String docNo, Instant runAt, long warehouseId,
                           boolean includeForecast, boolean includeSalesOrder,
                           String remark, String createdBy) {
    }

    private static final RowMapper<HeadRow> HEAD_MAPPER = (rs, rowNum) -> new HeadRow(
            rs.getLong("id"),
            rs.getString("doc_no"),
            fromDb(rs.getObject("run_at", LocalDateTime.class)),
            rs.getLong("warehouse_id"),
            toBool(rs.getObject("include_forecast")),
            toBool(rs.getObject("include_sales_order")),
            rs.getString("remark"),
            rs.getString("created_by"));

    private static final RowMapper<MrpSuggestion> SUGGESTION_MAPPER = (rs, rowNum) -> new MrpSuggestion(
            SuggestionType.valueOf(rs.getString("suggestion_type")),
            rs.getLong("product_id"),
            rs.getInt("level"),
            rs.getBigDecimal("gross_requirement"),
            rs.getBigDecimal("on_hand"),
            rs.getBigDecimal("net_requirement"),
            rs.getLong("base_unit_id"));

    private final JdbcTemplate jdbc;

    public JdbcMrpRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(MrpRun run) {
        insertHead(run);
        insertSuggestions(run);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MrpRun> findByDocNo(String docNo) {
        List<HeadRow> rows = jdbc.query(SELECT_HEAD + "WHERE doc_no = ?", HEAD_MAPPER, docNo);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        HeadRow row = rows.get(0);
        List<MrpSuggestion> suggestions = querySuggestions(row.id());
        return Optional.of(restore(row, suggestions));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MrpRun> searchHistory(int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM mrp_run", Long.class);
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResult<>(List.of(), 0, page, size);
        }

        List<HeadRow> rows = jdbc.query(
                SELECT_HEAD + "ORDER BY id DESC LIMIT ? OFFSET ?",
                HEAD_MAPPER, size, (long) (page - 1) * size);

        // 历史列表不回带建议行（按需加载），以空列表填充 suggestions
        // 调用方若需详情请通过 findByDocNo 按需加载
        List<MrpRun> items = rows.stream()
                .map(row -> restore(row, List.of()))
                .toList();
        return new PageResult<>(items, totalCount, page, size);
    }

    // ================================================================ 私有辅助

    private void insertHead(MrpRun run) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO mrp_run "
                            + "(doc_no, run_at, warehouse_id, include_forecast, include_sales_order, remark, "
                            + "created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            LocalDateTime runAtDb = toDb(run.getRunAt());
            ps.setString(1, run.getDocNo());
            ps.setObject(2, runAtDb);
            ps.setLong(3, run.getWarehouseId());
            ps.setBoolean(4, run.isIncludeForecast());
            ps.setBoolean(5, run.isIncludeSalesOrder());
            ps.setString(6, run.getRemark());
            ps.setString(7, run.getCreatedBy());
            ps.setObject(8, runAtDb);              // created_at = run_at
            ps.setString(9, run.getCreatedBy());   // updated_by = created_by
            ps.setObject(10, runAtDb);             // updated_at = run_at
            return ps;
        }, keyHolder);
        run.assignId(Objects.requireNonNull(keyHolder.getKey(), "未取得 MRP 运行自增主键").longValue());
    }

    private void insertSuggestions(MrpRun run) {
        List<MrpSuggestion> suggestions = run.getSuggestions();
        if (suggestions.isEmpty()) {
            return;
        }
        final long runId = run.getId();
        AtomicInteger counter = new AtomicInteger(0);
        jdbc.batchUpdate(
                "INSERT INTO mrp_suggestion "
                        + "(tenant_id, mrp_run_id, line_no, suggestion_type, product_id, level, "
                        + "gross_requirement, on_hand, net_requirement, base_unit_id) "
                        + "VALUES (0, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                suggestions, suggestions.size(), (ps, s) -> {
                    int lineNo = counter.incrementAndGet();
                    ps.setLong(1, runId);
                    ps.setInt(2, lineNo);
                    ps.setString(3, s.type().name());
                    ps.setLong(4, s.productId());
                    ps.setInt(5, s.level());
                    ps.setBigDecimal(6, s.grossRequirement());
                    ps.setBigDecimal(7, s.onHand());
                    ps.setBigDecimal(8, s.netRequirement());
                    ps.setLong(9, s.baseUnitId());
                });
    }

    /**
     * 按 MRP 运行 id 查询建议行（单次查询，按 line_no 排序）。
     */
    private List<MrpSuggestion> querySuggestions(long runId) {
        return jdbc.query(
                "SELECT suggestion_type, product_id, level, gross_requirement, "
                        + "on_hand, net_requirement, base_unit_id "
                        + "FROM mrp_suggestion WHERE mrp_run_id = ? ORDER BY line_no",
                SUGGESTION_MAPPER, runId);
    }

    /**
     * 批量回带建议行（一次 IN 查询，避免 N+1）。供未来批量加载详情使用。
     */
    @SuppressWarnings("unused")
    private Map<Long, List<MrpSuggestion>> fetchSuggestionsByRunIds(List<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(runIds.size(), "?"));
        Map<Long, List<MrpSuggestion>> byRun = new HashMap<>();
        jdbc.query(
                "SELECT mrp_run_id, suggestion_type, product_id, level, gross_requirement, "
                        + "on_hand, net_requirement, base_unit_id "
                        + "FROM mrp_suggestion WHERE mrp_run_id IN (" + placeholders + ") "
                        + "ORDER BY mrp_run_id, line_no",
                rs -> {
                    byRun.computeIfAbsent(rs.getLong("mrp_run_id"), k -> new ArrayList<>())
                            .add(new MrpSuggestion(
                                    SuggestionType.valueOf(rs.getString("suggestion_type")),
                                    rs.getLong("product_id"),
                                    rs.getInt("level"),
                                    rs.getBigDecimal("gross_requirement"),
                                    rs.getBigDecimal("on_hand"),
                                    rs.getBigDecimal("net_requirement"),
                                    rs.getLong("base_unit_id")));
                }, runIds.toArray());
        return byRun;
    }

    private static MrpRun restore(HeadRow row, List<MrpSuggestion> suggestions) {
        return MrpRun.restore(
                row.id(), row.docNo(), row.runAt(), row.warehouseId(),
                row.includeForecast(), row.includeSalesOrder(),
                row.remark(), row.createdBy(), suggestions);
    }

    private static boolean toBool(Object val) {
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.intValue() != 0;
        return false;
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return dateTime.toInstant(ZoneOffset.UTC);
    }
}
