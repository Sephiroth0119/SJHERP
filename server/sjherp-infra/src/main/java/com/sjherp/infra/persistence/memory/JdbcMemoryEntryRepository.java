package com.sjherp.infra.persistence.memory;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import com.sjherp.domain.common.PageResult;
import com.sjherp.domain.memory.MemoryEntry;
import com.sjherp.domain.memory.MemoryEntryQuery;
import com.sjherp.domain.memory.MemoryEntryRepository;
import com.sjherp.domain.memory.MemoryIndexStatus;
import com.sjherp.domain.memory.MemorySourceType;
import com.sjherp.domain.memory.MemoryStatus;
import com.sjherp.domain.memory.MemoryType;

/**
 * 大记忆 MySQL 真源仓储。
 *
 * <p>插入固化原文、逻辑键、版本、来源和创建审计；后续保存只更新治理状态、
 * 派生索引状态及更新审计。内容替换必须插入新版本，不能原地覆盖历史真源。
 */
@Transactional
public class JdbcMemoryEntryRepository implements MemoryEntryRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, memory_no, memory_key, version, previous_id,
                   memory_type, title, content, content_hash, source_type, source_ref,
                   status, valid_from, valid_to, index_status, indexed_collection,
                   embedding_model, embedding_dimension, retry_count, next_retry_at,
                   last_index_error, created_by, created_at, updated_by, updated_at
              FROM memory_entry
            """;

    private static final RowMapper<MemoryEntry> ROW_MAPPER = (rs, rowNum) -> MemoryEntry.restore(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getString("memory_no"),
            rs.getString("memory_key"),
            rs.getInt("version"),
            rs.getObject("previous_id", Long.class),
            MemoryType.valueOf(rs.getString("memory_type")),
            rs.getString("title"),
            rs.getString("content"),
            rs.getString("content_hash"),
            MemorySourceType.valueOf(rs.getString("source_type")),
            rs.getString("source_ref"),
            MemoryStatus.valueOf(rs.getString("status")),
            fromDb(rs.getObject("valid_from", LocalDateTime.class)),
            fromDbNullable(rs.getObject("valid_to", LocalDateTime.class)),
            MemoryIndexStatus.valueOf(rs.getString("index_status")),
            rs.getString("indexed_collection"),
            rs.getString("embedding_model"),
            rs.getObject("embedding_dimension", Integer.class),
            rs.getInt("retry_count"),
            fromDbNullable(rs.getObject("next_retry_at", LocalDateTime.class)),
            rs.getString("last_index_error"),
            rs.getString("created_by"),
            fromDb(rs.getObject("created_at", LocalDateTime.class)),
            rs.getString("updated_by"),
            fromDb(rs.getObject("updated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public JdbcMemoryEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    @Override
    public void save(MemoryEntry entry) {
        Objects.requireNonNull(entry, "大记忆不能为空");
        if (entry.getId() == null) {
            insert(entry);
        } else {
            update(entry);
        }
    }

    private void insert(MemoryEntry entry) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO memory_entry (
                        tenant_id, memory_no, memory_key, version, previous_id,
                        memory_type, title, content, content_hash, source_type, source_ref,
                        status, valid_from, valid_to, index_status, indexed_collection,
                        embedding_model, embedding_dimension, retry_count, next_retry_at,
                        last_index_error, created_by, created_at, updated_by, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            statement.setLong(index++, entry.getTenantId());
            statement.setString(index++, entry.getMemoryNo());
            statement.setString(index++, entry.getMemoryKey());
            statement.setInt(index++, entry.getVersion());
            statement.setObject(index++, entry.getPreviousId());
            statement.setString(index++, entry.getMemoryType().name());
            statement.setString(index++, entry.getTitle());
            statement.setString(index++, entry.getContent());
            statement.setString(index++, entry.getContentHash());
            statement.setString(index++, entry.getSourceType().name());
            statement.setString(index++, entry.getSourceRef());
            statement.setString(index++, entry.getStatus().name());
            statement.setObject(index++, toDb(entry.getValidFrom()));
            statement.setObject(index++, toDbNullable(entry.getValidTo()));
            statement.setString(index++, entry.getIndexStatus().name());
            statement.setString(index++, entry.getIndexedCollection());
            statement.setString(index++, entry.getEmbeddingModel());
            statement.setObject(index++, entry.getEmbeddingDimension());
            statement.setInt(index++, entry.getRetryCount());
            statement.setObject(index++, toDbNullable(entry.getNextRetryAt()));
            statement.setString(index++, entry.getLastIndexError());
            statement.setString(index++, entry.getCreatedBy());
            statement.setObject(index++, toDb(entry.getCreatedAt()));
            statement.setString(index++, entry.getUpdatedBy());
            statement.setObject(index, toDb(entry.getUpdatedAt()));
            return statement;
        }, keyHolder);
        Number key = Objects.requireNonNull(keyHolder.getKey(), "未取得大记忆自增主键");
        entry.assignId(key.longValue());
    }

    private void update(MemoryEntry entry) {
        int affected = jdbc.update("""
                UPDATE memory_entry
                   SET status = ?, valid_to = ?, index_status = ?, indexed_collection = ?,
                       embedding_model = ?, embedding_dimension = ?, retry_count = ?,
                       next_retry_at = ?, last_index_error = ?, updated_by = ?, updated_at = ?
                 WHERE tenant_id = ? AND id = ?
                """,
                entry.getStatus().name(), toDbNullable(entry.getValidTo()),
                entry.getIndexStatus().name(), entry.getIndexedCollection(),
                entry.getEmbeddingModel(), entry.getEmbeddingDimension(), entry.getRetryCount(),
                toDbNullable(entry.getNextRetryAt()), entry.getLastIndexError(),
                entry.getUpdatedBy(), toDb(entry.getUpdatedAt()),
                entry.getTenantId(), entry.getId());
        if (affected != 1) {
            throw new IllegalStateException("大记忆更新失败或租户不匹配: " + entry.getMemoryNo());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemoryEntry> findByMemoryNo(String memoryNo) {
        List<MemoryEntry> rows = jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = 0 AND memory_no = ?
                """, ROW_MAPPER, memoryNo);
        return first(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemoryEntry> findActiveByMemoryKey(String memoryKey) {
        List<MemoryEntry> rows = jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = 0 AND memory_key = ? AND status = 'ACTIVE'
                ORDER BY version DESC LIMIT 1
                """, ROW_MAPPER, memoryKey);
        return first(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MemoryEntry> search(MemoryEntryQuery query) {
        Objects.requireNonNull(query, "查询条件不能为空");
        StringBuilder where = new StringBuilder("WHERE tenant_id = 0 ");
        List<Object> args = new ArrayList<>();
        if (query.memoryType() != null) {
            where.append("AND memory_type = ? ");
            args.add(query.memoryType().name());
        }
        if (query.status() != null) {
            where.append("AND status = ? ");
            args.add(query.status().name());
        }
        if (query.indexStatus() != null) {
            where.append("AND index_status = ? ");
            args.add(query.indexStatus().name());
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM memory_entry " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;
        if (totalCount == 0L) {
            return new PageResult<>(List.of(), 0L, query.page(), query.size());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((query.page() - 1) * query.size());
        List<MemoryEntry> rows = jdbc.query(
                SELECT_COLUMNS + where + "ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());
        return new PageResult<>(rows, totalCount, query.page(), query.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryEntry> findIndexCandidates(Instant dueAt, int limit) {
        requireLimit(limit);
        Objects.requireNonNull(dueAt, "到期时间不能为空");
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = 0
                  AND status = 'ACTIVE'
                  AND (index_status = 'PENDING'
                       OR (index_status = 'FAILED' AND next_retry_at IS NOT NULL
                           AND next_retry_at <= ?))
                ORDER BY id
                LIMIT ?
                """, ROW_MAPPER, toDb(dueAt), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryEntry> findActiveAfterId(long afterId, int limit) {
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId 不能为负数");
        }
        requireLimit(limit);
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = 0 AND status = 'ACTIVE' AND id > ?
                ORDER BY id LIMIT ?
                """, ROW_MAPPER, afterId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryEntry> findRecallableByIds(List<Long> ids, long tenantId, Instant asOf) {
        Objects.requireNonNull(ids, "召回主键列表不能为空");
        if (ids.isEmpty()) {
            return List.of();
        }
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户主键不能为负数");
        }
        Objects.requireNonNull(asOf, "召回时间不能为空");
        if (ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("召回主键必须为正数且一次不得超过 200 个");
        }

        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 3);
        args.add(tenantId);
        args.add(toDb(asOf));
        args.add(toDb(asOf));
        args.addAll(ids);
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = ?
                  AND status = 'ACTIVE'
                  AND index_status = 'INDEXED'
                  AND valid_from <= ?
                  AND (valid_to IS NULL OR valid_to > ?)
                  AND id IN (
                """ + placeholders + ") ORDER BY id", ROW_MAPPER, args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryEntry> findDuplicateCandidates(long tenantId, int groupLimit) {
        requireTenantAndGroupLimit(tenantId, groupLimit);
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = ?
                  AND status = 'ACTIVE'
                  AND id IN (
                    SELECT candidate_entry.id
                      FROM memory_entry candidate_entry
                      JOIN (
                        SELECT memory_type, content_hash
                          FROM memory_entry
                         WHERE tenant_id = ? AND status = 'ACTIVE'
                         GROUP BY memory_type, content_hash
                        HAVING COUNT(*) > 1
                         ORDER BY MAX(id) DESC
                         LIMIT ?
                      ) candidate_group
                        ON candidate_group.memory_type = candidate_entry.memory_type
                       AND candidate_group.content_hash = candidate_entry.content_hash
                     WHERE candidate_entry.tenant_id = ?
                       AND candidate_entry.status = 'ACTIVE'
                  )
                ORDER BY id DESC
                """, ROW_MAPPER, tenantId, tenantId, groupLimit, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryEntry> findConflictCandidates(long tenantId, int groupLimit) {
        requireTenantAndGroupLimit(tenantId, groupLimit);
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = ?
                  AND status = 'ACTIVE'
                  AND id IN (
                    SELECT candidate_entry.id
                      FROM memory_entry candidate_entry
                      JOIN (
                        SELECT memory_type, BINARY title AS title_key
                          FROM memory_entry
                         WHERE tenant_id = ? AND status = 'ACTIVE'
                         GROUP BY memory_type, BINARY title
                        HAVING COUNT(DISTINCT content_hash) > 1
                         ORDER BY MAX(id) DESC
                         LIMIT ?
                      ) candidate_group
                        ON candidate_group.memory_type = candidate_entry.memory_type
                       AND candidate_group.title_key = BINARY candidate_entry.title
                     WHERE candidate_entry.tenant_id = ?
                       AND candidate_entry.status = 'ACTIVE'
                  )
                ORDER BY id DESC
                """, ROW_MAPPER, tenantId, tenantId, groupLimit, tenantId);
    }

    @Override
    public List<MemoryEntry> findByMemoryNosForUpdate(List<String> memoryNos) {
        Objects.requireNonNull(memoryNos, "记忆编号列表不能为空");
        if (memoryNos.isEmpty() || memoryNos.size() > 50
                || memoryNos.stream().anyMatch(no -> no == null || no.isBlank())) {
            throw new IllegalArgumentException("记忆编号必须非空且数量在 1 到 50 之间");
        }
        String placeholders = String.join(",", Collections.nCopies(memoryNos.size(), "?"));
        return jdbc.query(SELECT_COLUMNS + """
                WHERE tenant_id = 0
                  AND memory_no IN (
                """ + placeholders + ") FOR UPDATE", ROW_MAPPER, memoryNos.toArray());
    }

    private static Optional<MemoryEntry> first(List<MemoryEntry> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("查询数量必须在 1 到 1000 之间");
        }
    }

    private static void requireTenantAndGroupLimit(long tenantId, int groupLimit) {
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户主键不能为负数");
        }
        if (groupLimit < 1 || groupLimit > 100) {
            throw new IllegalArgumentException("候选组数量必须在 1 到 100 之间");
        }
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static LocalDateTime toDbNullable(Instant instant) {
        return instant == null ? null : toDb(instant);
    }

    private static Instant fromDb(LocalDateTime dateTime) {
        return Objects.requireNonNull(dateTime, "数据库时间不能为空").toInstant(ZoneOffset.UTC);
    }

    private static Instant fromDbNullable(LocalDateTime dateTime) {
        return dateTime == null ? null : fromDb(dateTime);
    }
}
